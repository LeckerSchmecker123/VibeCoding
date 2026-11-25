## Chess.com-Botpartien: Zugempfehlung

Das Java-Programm liest die Stellung aus einem offenen Chess.com-Computer-Botspiel
in Chrome und zeigt Stockfishs Zugempfehlung an. Es führt den Zug nicht selbst aus.
Es ist auf Botpartien beschränkt, nicht auf Partien gegen Menschen.

### Voraussetzungen

- Java 17 oder neuer
- Google Chrome
- Stockfish für macOS (Apple Silicon oder Intel)

### Stockfish auf dem Mac einrichten

Der Befehl `brew install stockfish` funktioniert erst, wenn Homebrew installiert ist.
Wenn dein Terminal `brew: command not found` meldet:

1. Öffne <https://brew.sh> und folge dort der Installationsanleitung für Homebrew.
2. Falls der Installer danach weitere Terminal-Befehle unter „Next steps“ anzeigt,
   führe auch diese aus. Schließe das Terminal danach und öffne es erneut.
3. Prüfe die Installation mit:

   ```sh
   brew --version
   ```

4. Installiere dann Stockfish:

   ```sh
   brew install stockfish
   ```

#### Ohne Homebrew

Lade die passende macOS-Version von der [offiziellen Stockfish-Downloadseite](https://stockfishchess.org/download/)
herunter und entpacke sie. Wähle anschließend im Programm über **Durchsuchen…**
die ausführbare Datei `stockfish` aus — nicht die ZIP-Datei.

### Start auf macOS

1. Öffne ein separates Chrome-Profil mit Remote-Debugging:

   ```sh
   open -na "Google Chrome" --args --remote-debugging-port=9222 \
     --user-data-dir="$HOME/.chesscom-bot-profile"
   ```

2. Öffne **in genau diesem Chrome-Fenster** eine laufende Partie gegen einen
   Chess.com-Bot. Die deutsche Route `/de/play/computer` und die englische Route
   `/play/computer` werden unterstützt. Safari oder ein anderes, normal gestartetes
   Chrome-Fenster wird nicht erkannt.
3. Kompiliere und starte die App im Projektordner:

   ```sh
   javac --release 17 Chess.java
   java Chess
   ```

4. Klicke in der App auf **Zugempfehlung starten**. Falls Stockfish nicht automatisch
   erkannt wird, kannst du die Engine-Datei über **Durchsuchen…** auswählen.

Auf Apple Silicon liegt die Homebrew-Engine meist unter
`/opt/homebrew/bin/stockfish`, auf Intel-Macs unter `/usr/local/bin/stockfish`.
Die App liest den Brettzustand lokal über Chrome Remote Debugging; sie erfasst keine
Screenshots und übermittelt keine Zugangsdaten.

Chess.com nimmt Botpartien ausdrücklich von der Engine-Hilfe-Regel aus:
<https://www.chess.com/legal/fair-play>. Diese App ist technisch auf Botpartien begrenzt.

zum benutzen erst : open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir="$HOME/.chesscom-bot-profile"

dann : cd /Users/lmuth/VSCode/VibeCoding
javac --release 17 Chess.java
java Chess

-> muss nochmal schauen gibt nun einen fehler mit dem finden einer datei (hab in ordner verschoben)