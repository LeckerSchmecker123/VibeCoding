import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public class Chess {
    private static final Pattern JSON_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern JSON_VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*}");
    private static final Pattern BOT_GAME_PATH =
            Pattern.compile("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?play/computer(?:/.*)?$");

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Verwendung: java Chess [stockfish-programmpfad]");
            System.exit(2);
        }

        String initialPath = args.length == 1 ? args[0] : locateStockfish();
        SwingUtilities.invokeLater(() -> new ChessWindow(initialPath).setVisible(true));
    }

    private static String locateStockfish() {
        Set<Path> candidates = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(System.getProperty("path.separator"))) {
                if (!directory.isBlank()) {
                    candidates.add(Path.of(directory).resolve("stockfish"));
                }
            }
        }
        candidates.add(Path.of("/opt/homebrew/bin/stockfish"));
        candidates.add(Path.of("/usr/local/bin/stockfish"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return "";
    }

    private static Path resolveStockfish(String configuredPath) throws IOException {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IOException("Stockfish fehlt. Installiere es mit `brew install stockfish` "
                    + "oder wähle die Engine-Datei über „Durchsuchen“ aus.");
        }

        Path configured = Path.of(configuredPath.trim());
        if (configured.isAbsolute() || configuredPath.contains("/")) {
            if (Files.isRegularFile(configured) && Files.isExecutable(configured)) {
                return configured.toAbsolutePath();
            }
            throw new IOException("Stockfish wurde unter diesem Pfad nicht gefunden "
                    + "oder ist nicht ausführbar: " + configuredPath);
        }

        Set<Path> candidates = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(System.getProperty("path.separator"))) {
                if (!directory.isBlank()) {
                    candidates.add(Path.of(directory).resolve(configuredPath.trim()));
                }
            }
        }
        candidates.add(Path.of("/opt/homebrew/bin").resolve(configuredPath.trim()));
        candidates.add(Path.of("/usr/local/bin").resolve(configuredPath.trim()));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        throw new IOException("Stockfish wurde nicht gefunden. Installiere es mit "
                + "`brew install stockfish` oder wähle die Engine-Datei über „Durchsuchen“ aus.");
    }

    private record BoardState(String fen, int playingAs, boolean gameOver) {
        static BoardState parse(String encoded) {
            String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 3) {
                throw new IllegalStateException("Unerwartete Antwort vom Chess.com-Brett.");
            }
            return new BoardState(fields[0], Integer.parseInt(fields[1]), fields[2].equals("1"));
        }

        String playerColor() {
            return switch (playingAs) {
                case 1 -> "w";
                case 2 -> "b";
                default -> throw new IllegalStateException(
                        "Die Spielerseite konnte auf dem Brett nicht erkannt werden.");
            };
        }
    }

    private static final class ChessWindow extends JFrame {
        private static final long serialVersionUID = 1L;

        private final JTextField enginePath;
        private final JLabel status = new JLabel(
                "Warte auf eine Botpartie in Chrome.", SwingConstants.CENTER);
        private final JLabel recommendation = new JLabel("—", SwingConstants.CENTER);
        private final JLabel turnInfo = new JLabel(" ", SwingConstants.CENTER);
        private final JButton startButton = new JButton("Zugempfehlung starten");
        private final JSlider thinkingTime = new JSlider(500, 5000, 1500);
        private transient AnalysisController controller;
        private boolean closing;

        ChessWindow(String initialPath) {
            super("Chess.com Bot - Zugempfehlung");
            enginePath = new JTextField(initialPath, 30);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setLayout(new BorderLayout(8, 8));

            JLabel instructions = new JLabel(
                    "<html>Öffne zuerst eine Botpartie in Chrome. "
                            + "Die App liest nur die Stellung und führt keine Züge aus.</html>",
                    SwingConstants.CENTER);
            instructions.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
            add(instructions, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(4, 12));
            status.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
            recommendation.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
            turnInfo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            center.add(status, BorderLayout.NORTH);
            center.add(recommendation, BorderLayout.CENTER);
            center.add(turnInfo, BorderLayout.SOUTH);
            add(center, BorderLayout.CENTER);

            JPanel controls = new JPanel(new BorderLayout(6, 6));
            JPanel engineRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
            engineRow.add(new JLabel("Stockfish:"));
            engineRow.add(enginePath);
            JButton browseButton = new JButton("Durchsuchen…");
            engineRow.add(browseButton);
            controls.add(engineRow, BorderLayout.NORTH);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER));
            actions.add(new JLabel("Suchzeit (ms):"));
            thinkingTime.setMajorTickSpacing(1000);
            thinkingTime.setPaintTicks(true);
            thinkingTime.setPreferredSize(new Dimension(180, 40));
            actions.add(thinkingTime);
            actions.add(startButton);
            controls.add(actions, BorderLayout.CENTER);
            controls.setBorder(BorderFactory.createEmptyBorder(4, 8, 12, 8));
            add(controls, BorderLayout.SOUTH);

            browseButton.addActionListener(event -> chooseEngine());
            startButton.addActionListener(event -> toggleAnalysis());
            thinkingTime.addChangeListener(event -> {
                if (controller != null) {
                    controller.setThinkingTime(thinkingTime.getValue());
                }
            });
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    closing = true;
                    if (controller != null) {
                        controller.stop();
                    }
                    dispose();
                }
            });

            setMinimumSize(new Dimension(620, 320));
            setSize(760, 390);
            setLocationRelativeTo(null);
        }

        private void chooseEngine() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Stockfish-Programm auswählen");
            if (!enginePath.getText().isBlank()) {
                Path current = Path.of(enginePath.getText().trim());
                if (Files.isDirectory(current)) {
                    chooser.setCurrentDirectory(current.toFile());
                } else if (current.getParent() != null && Files.isDirectory(current.getParent())) {
                    chooser.setCurrentDirectory(current.getParent().toFile());
                }
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                enginePath.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        }

        private void toggleAnalysis() {
            if (controller != null) {
                status.setText("Analyse wird beendet …");
                controller.stop();
                return;
            }
            try {
                Path executable = resolveStockfish(enginePath.getText());
                enginePath.setText(executable.toString());
                controller = new AnalysisController(this, executable.toString(),
                        thinkingTime.getValue());
                startButton.setText("Analyse stoppen");
                status.setText("Verbinde mit Chrome und starte Stockfish …");
                recommendation.setText("—");
                turnInfo.setText(" ");
                controller.start();
            } catch (IOException e) {
                status.setText(e.getMessage());
            }
        }

        void showStatus(String message) {
            SwingUtilities.invokeLater(() -> status.setText(message));
        }

        void showRecommendation(String move, String side) {
            SwingUtilities.invokeLater(() -> {
                recommendation.setText(move);
                turnInfo.setText("Empfehlung für " + side);
            });
        }

        void clearRecommendation(String message) {
            SwingUtilities.invokeLater(() -> {
                recommendation.setText("—");
                turnInfo.setText(message);
            });
        }

        void analysisFinished(AnalysisController finishedController) {
            SwingUtilities.invokeLater(() -> {
                if (controller == finishedController) {
                    controller = null;
                    if (!closing) {
                        startButton.setText("Zugempfehlung starten");
                    }
                }
            });
        }
    }

    private static final class AnalysisController implements Runnable {
        private final ChessWindow window;
        private final String stockfishPath;
        private final AtomicInteger thinkingTime;
        private volatile boolean stopped;
        private Thread thread;

        AnalysisController(ChessWindow window, String stockfishPath, int thinkingTime) {
            this.window = window;
            this.stockfishPath = stockfishPath;
            this.thinkingTime = new AtomicInteger(thinkingTime);
        }

        void start() {
            thread = new Thread(this, "chess-position-analysis");
            thread.start();
        }

        void setThinkingTime(int milliseconds) {
            thinkingTime.set(milliseconds);
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try (CdpClient browser = CdpClient.connect(9222);
                 Stockfish engine = new Stockfish(stockfishPath)) {
                window.showStatus("Verbunden. Warte auf deinen Zug gegen den Bot.");
                String analyzedFen = "";

                while (!stopped) {
                    BoardState state = browser.readState();
                    if (state.gameOver) {
                        window.clearRecommendation("Die Botpartie ist beendet.");
                        window.showStatus("Partie beendet.");
                        break;
                    }

                    String playerColor = state.playerColor();
                    String sideToMove = activeColor(state.fen);
                    if (!sideToMove.equals(playerColor)) {
                        analyzedFen = "";
                        window.clearRecommendation("Warte auf den Zug des Bots.");
                    } else if (!state.fen.equals(analyzedFen)) {
                        window.showStatus("Analysiere die aktuelle Stellung …");
                        String move = engine.bestMove(state.fen, thinkingTime.get());
                        if (move.equals("0000") || move.equals("(none)")) {
                            window.clearRecommendation("Keine legalen Züge verfügbar.");
                            window.showStatus("Die Partie ist beendet.");
                            break;
                        }
                        analyzedFen = state.fen;
                        String side = playerColor.equals("w") ? "Weiß" : "Schwarz";
                        window.showRecommendation(formatMove(move), side);
                        window.showStatus("Zugempfehlung bereit. Du führst den Zug selbst aus.");
                    }
                    Thread.sleep(400);
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                if (!stopped) {
                    window.showStatus("Fehler: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!stopped) {
                    window.showStatus("Analyse wurde unterbrochen.");
                }
            } finally {
                window.analysisFinished(this);
            }
        }
    }

    private static String activeColor(String fen) {
        String[] fields = fen.split(" ");
        if (fields.length < 2 || (!fields[1].equals("w") && !fields[1].equals("b"))) {
            throw new IllegalStateException("Chess.com lieferte eine ungültige Stellung.");
        }
        return fields[1];
    }

    private static String formatMove(String uci) {
        if (!uci.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            throw new IllegalStateException("Stockfish lieferte einen ungültigen Zug: " + uci);
        }
        String move = uci.substring(0, 2) + " → " + uci.substring(2, 4);
        if (uci.length() == 5) {
            move += " = " + switch (uci.charAt(4)) {
                case 'q' -> "Dame";
                case 'r' -> "Turm";
                case 'b' -> "Läufer";
                case 'n' -> "Springer";
                default -> throw new IllegalStateException("Unbekannte Umwandlungsfigur.");
            };
        }
        return move;
    }

    private static final class CdpClient implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final StringBuilder fragments = new StringBuilder();
        private WebSocket socket;

        static CdpClient connect(int port) throws IOException, InterruptedException {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/json/list");
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Chrome-Remote-Debugging antwortet mit HTTP "
                        + response.statusCode() + ".");
            }

            String webSocketUrl = findBotPage(response.body());
            CdpClient client = new CdpClient();
            try {
                client.socket = http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .buildAsync(URI.create(webSocketUrl), client)
                        .join();
            } catch (RuntimeException e) {
                throw new IOException("Verbindung zum Chrome-Botfenster fehlgeschlagen: "
                        + e.getMessage(), e);
            }
            return client;
        }

        private static String findBotPage(String targets) throws IOException {
            Matcher objects = JSON_OBJECT.matcher(targets);
            while (objects.find()) {
                String object = objects.group();
                String url = jsonField(object, "url");
                String webSocketUrl = jsonField(object, "webSocketDebuggerUrl");
                if (url != null && isBotGameUrl(url)
                        && webSocketUrl != null) {
                    return webSocketUrl.replace("\\/", "/");
                }
            }
            throw new IOException("Keine aktive Chess.com-Botpartie gefunden. "
                    + "Öffne die laufende Botpartie in dem Chrome-Fenster, das mit "
                    + "--remote-debugging-port=9222 gestartet wurde (nicht in Safari).");
        }

        private static boolean isBotGameUrl(String url) {
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                String path = uri.getPath();
                boolean chessCom = "chess.com".equals(host) || "www.chess.com".equals(host);
                boolean computerPage = path != null && BOT_GAME_PATH.matcher(path).matches();
                return chessCom && computerPage;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private static String jsonField(String json, String field) {
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(field)
                    + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
            return matcher.find() ? matcher.group(1) : null;
        }

        BoardState readState() throws IOException, InterruptedException {
            String expression = "(()=>{"
                    + "if(!/^\\/(?:[a-z]{2}(?:-[a-z]{2})?\\/)?play\\/computer(?:\\/|$)/"
                    + ".test(location.pathname))return 'NOT_BOT';"
                    + "const b=document.querySelector('wc-chess-board#board-play-computer');"
                    + "if(!b||!b.game||!b.state)return 'NO_BOARD';"
                    + "return encodeURIComponent([b.game.getFEN(),b.game.getPlayingAs(),"
                    + "b.state.isGameOver?1:0].join('|'));"
                    + "})()";
            String result = evaluate(expression);
            if (result.equals("NOT_BOT")) {
                throw new IOException("Nur Chess.com-Partien gegen Computer-Bots werden unterstützt.");
            }
            if (result.equals("NO_BOARD")) {
                throw new IOException("Kein aktives Bot-Schachbrett gefunden. Starte zuerst eine Partie.");
            }
            return BoardState.parse(result);
        }

        private String evaluate(String expression) throws IOException, InterruptedException {
            String response = command("Runtime.evaluate", "{\"expression\":\""
                    + jsonEscape(expression) + "\",\"returnByValue\":true}");
            Matcher value = JSON_VALUE.matcher(response);
            if (!value.find()) {
                throw new IOException("Chrome lieferte keinen lesbaren Brettzustand.");
            }
            return value.group(1);
        }

        private String command(String method, String params) throws IOException, InterruptedException {
            int id = nextId.getAndIncrement();
            String message = "{\"id\":" + id + ",\"method\":\"" + method
                    + "\",\"params\":" + params + "}";
            try {
                socket.sendText(message, true).join();
            } catch (RuntimeException e) {
                throw new IOException("Chrome-Verbindung wurde geschlossen: "
                        + e.getMessage(), e);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                String response = messages.poll(250, TimeUnit.MILLISECONDS);
                if (response == null) {
                    continue;
                }
                Matcher idMatcher = JSON_ID.matcher(response);
                if (idMatcher.find() && Integer.parseInt(idMatcher.group(1)) == id) {
                    if (response.contains("\"error\"")) {
                        throw new IOException("Chrome-Remote-Debugging-Fehler: " + response);
                    }
                    return response;
                }
                if (response.startsWith("CDP_ERROR:")) {
                    throw new IOException(response.substring("CDP_ERROR:".length()));
                }
            }
            throw new IOException("Zeitüberschreitung bei der Verbindung zu Chrome.");
        }

        private static String jsonEscape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (fragments) {
                fragments.append(data);
                if (last) {
                    messages.offer(fragments.toString());
                    fragments.setLength(0);
                }
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            messages.offer("CDP_ERROR: " + error.getMessage());
        }

        @Override
        public void close() {
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "Done");
            }
        }
    }

    private static final class Stockfish implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final BlockingQueue<String> output = new LinkedBlockingQueue<>();

        Stockfish(String executable) throws IOException, InterruptedException {
            process = new ProcessBuilder(executable).redirectErrorStream(true).start();
            input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        output.offer(line);
                    }
                } catch (IOException e) {
                    output.offer("ENGINE_IO_ERROR: " + e.getMessage());
                }
            }, "stockfish-output");
            reader.setDaemon(true);
            reader.start();

            try {
                send("uci");
                waitFor("uciok", 30);
                send("setoption name Threads value 2");
                send("setoption name Hash value 128");
                send("isready");
                waitFor("readyok", 30);
            } catch (IOException | InterruptedException e) {
                process.destroyForcibly();
                try {
                    input.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }
        }

        String bestMove(String fen, int milliseconds) throws IOException, InterruptedException {
            send("position fen " + fen);
            send("go movetime " + milliseconds);
            String[] fields = waitFor("bestmove ", 30).split("\\s+");
            if (fields.length < 2) {
                throw new IOException("Unerwartete Antwort von Stockfish.");
            }
            return fields[1];
        }

        private void send(String command) throws IOException {
            input.write(command);
            input.newLine();
            input.flush();
        }

        private String waitFor(String prefix, int timeoutSeconds)
                throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                String line = output.poll(250, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (!process.isAlive()) {
                        throw new IOException("Stockfish wurde unerwartet beendet.");
                    }
                    continue;
                }
                if (line.startsWith("ENGINE_IO_ERROR:")) {
                    throw new IOException(line);
                }
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
            throw new IOException("Zeitüberschreitung beim Warten auf Stockfish ("
                    + prefix.trim() + ").");
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            if (process.isAlive()) {
                try {
                    input.write("quit");
                    input.newLine();
                    input.flush();
                } catch (IOException e) {
                    failure = e;
                }
            }
            try {
                input.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (process.isAlive()) {
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    IOException interrupted = new IOException(
                            "Beenden von Stockfish wurde unterbrochen.", e);
                    if (failure == null) {
                        failure = interrupted;
                    } else {
                        failure.addSuppressed(interrupted);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}