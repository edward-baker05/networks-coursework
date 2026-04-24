# TFTP Technical Walkthrough — Video Script

**Target runtime:** 5 min 45 s (of 7 min max = 82%).
**Coursework reference:** `brief.md` → "Voice-over Screen Recording Video (25%)", sub-tasks 1–5.
**Scope:** UDP implementation only (the five sub-tasks all target RFC 1350 behaviour).

---

## 0. Pre-recording setup (do this BEFORE you hit record — not part of the 7 min)

### 0.1 Windows and layout
Open **IntelliJ IDEA** with the networks-coursework folder. Maximise it to full screen. Close every tool window except the **Project** tree (left) and the **Debug** tool window (hidden for now).

Open these five tabs in the editor in this left-to-right order, so you can click through them in the order the script needs:

1. `TFTP-UDP-Server/src/main/java/tftp/udp/server/TftpPacket.java`
2. `TFTP-UDP-Client/src/main/java/tftp/udp/client/TftpUdpClient.java`
3. `TFTP-UDP-Server/src/main/java/tftp/udp/server/TftpUdpServer.java`
4. `TFTP-UDP-Server/src/main/java/tftp/udp/server/TftpTransferHandler.java`
5. `TFTP-UDP-Server/src/main/java/tftp/udp/server/TftpPacket.java` (again — you will alternate)

Make the editor font large enough that every line is readable at 1080 p (View → Appearance → Zoom). 16 pt is a good baseline.

### 0.2 Run configurations
Create two IntelliJ **Application** run configurations (Run → Edit Configurations → +). Keep the defaults; set only:

- **Run-Server** — main class `tftp.udp.server.TftpUdpServer`, module `TFTP-UDP-Server`, working directory `$PROJECT_DIR$`.
- **Run-Client-GET-512** — main class `tftp.udp.client.TftpUdpClient`, module `TFTP-UDP-Client`, program arguments `get 512.txt`, working directory `$PROJECT_DIR$/client-cwd` (create this empty folder first so the retrieved file lands somewhere visible).

You will edit the program arguments of Run-Client-GET-512 live during the recording to run the different scenarios (`get 512.txt`, `put 512.txt`, `get missing.txt`). Have the Edit Configurations dialog's keyboard shortcut ready: `⌘⇧A` → type "Edit Configurations".

### 0.3 Pre-place breakpoints
Click in the gutter of these exact lines so a red dot appears. Do this once now — you will enable/disable them with `⌘F8` during the video.

**`TftpPacket.java` (server copy):**
- Line 48 — `dos.writeShort(opcode);` (inside `buildRequest`)
- Line 71 — `bb.putShort((short) OP_DATA);` (inside `buildData`)

**`TftpUdpClient.java`:**
- Line 88 — `byte[] rrq = TftpPacket.buildRRQ(filename);`
- Line 125 — `if (op == TftpPacket.OP_ERROR) {`
- Line 145 — `if (data.length < TftpPacket.BLOCK_SIZE) {`
- Line 177 — `byte[] wrq = TftpPacket.buildWRQ(filename);`

**`TftpUdpServer.java`:**
- Line 73 — `int opcode = TftpPacket.getOpcode(request);`
- Line 84 — `pool.submit(new TftpTransferHandler(...));`

**`TftpTransferHandler.java`:**
- Line 60 — `if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {`
- Line 79 — `done = (bytesRead < TftpPacket.BLOCK_SIZE);`
- Line 107 — `if (op == TftpPacket.OP_ACK && TftpPacket.getBlockNumber(resp) == blockNum) {`

**Before recording starts:** disable every breakpoint (Run → View Breakpoints → untick "Enabled"). You will re-enable them one pair at a time as the script tells you to.

### 0.4 File sanity
- Confirm `512.txt`, `medium.txt`, `large.txt`, `test_image.jpg` exist in the repo root.
- Confirm `client-cwd/` exists and is empty. If a `512.txt` already sits in it from a prior rehearsal, delete it.
- Make sure no other JVM is running on port **6969**: `lsof -i :6969` from a terminal — output must be empty.

### 0.5 Audio / screen
- Quit Slack, Mail, notifications off (Do Not Disturb).
- Mic test: say a sentence, play back, check levels.
- Recorder set to 1080 p, 30 fps. Crop to the IntelliJ window after if possible.

---

## 1. Recording — running script

> **Read the lines in quotes verbatim.** Everything outside quotes is an on-screen action. Timings are cumulative; the right-hand column is the target time when that line should END.

---

### § Intro (0:00 → 0:15)

**Action:** IntelliJ is visible with `TftpPacket.java` (server copy) open at line 1.

> "This is my TFTP implementation for the networks coursework. I'll cover the packet header layout, packetisation, how RRQ and WRQ are initiated, how I signal file-not-found, and how both ends detect completion — all on the UDP version, per RFC 1350."

**Cumulative time:** 0:15

---

### § 1. Packet Header (0:15 → 1:15) — 5%

**Action:** Tab to `TftpPacket.java`. Scroll so lines 18–30 are visible.

> "All packet construction lives in `TftpPacket.java`. Lines 20 to 24 define the five RFC 1350 opcodes as constants: RRQ is 1, WRQ is 2, DATA is 3, ACK is 4, ERROR is 5."

**Action:** Highlight lines 20–24 with the mouse as you say this.

> "Line 28 defines the 512-byte block size, and line 29 sets the maximum packet size to 4 plus 512 — that's the 4-byte header plus a full data block." *(Cumulative: ~0:35)*

**Action:** Scroll to lines 45–57 (`buildRequest`).

> "For RRQ and WRQ, the header is an opcode followed by a null-terminated filename and the mode string 'octet'. I build this with a `DataOutputStream` — `writeShort` on line 48 puts the two-byte opcode in big-endian order, then the filename bytes, a zero byte, then the literal 'octet' and another zero byte. That matches the RFC 1350 figure 5-1 layout exactly."

**Action:** Scroll to lines 68–86 (`buildData` and `buildAck`).

> "DATA and ACK share the same two-field header: a two-byte opcode then a two-byte block number. `buildData` on line 68 writes opcode 3 and the block number into the first 4 bytes of a fixed-size buffer, then appends up to 512 payload bytes. `buildAck` on line 80 produces a 4-byte packet containing only the opcode and block number — that's the sequence number from the RFC." *(Cumulative: ~1:05)*

**Action:** Scroll to lines 89–100 (`buildError`).

> "And ERROR packets — opcode 5, a two-byte error code, a null-terminated message. I use this for error code 1, file not found."

**Cumulative time:** 1:15

---

### § 2. Packetisation (1:15 → 2:15) — 5%

**Action:** Tab to `TftpUdpClient.java`. Scroll to lines 220–234 in `doPut`.

> "Packetisation happens on whichever side is sending DATA — for a PUT, it's the client. The file is read into a byte array once, then chunked into 512-byte blocks in the loop starting at line 226."

**Action:** Highlight lines 226–231.

> "On line 227 I compute `Math.min(512, remaining)` — so every block is a full 512 bytes except possibly the last. Line 228 is the RFC 1350 termination rule: the block whose length is less than 512 bytes marks the end of the transfer. If the file size is an exact multiple of 512 — like the provided `512.txt`, which is 512 bytes, or `medium.txt`, which is exactly 320 blocks — I still need a final zero-byte DATA packet to signal completion, and `done = (length < 512)` correctly flags block 2 or block 321 in those cases." *(Cumulative: ~1:45)*

**Action:** Highlight lines 230, then click on `buildData` to peek at its body (or just point at line 68 of `TftpPacket.java` if it's still visible in a split).

> "Line 230 hands the offset, length, and block number to `buildData`, which prepends the 4-byte header we just discussed. The block number is a 16-bit counter that starts at 1 and is incremented on line 263 after each ACK. `large.txt` at 323,640 bytes for example produces 633 DATA packets: 632 full blocks plus a 56-byte final block."

**Action:** Tab to `TftpTransferHandler.java`, scroll to lines 74–84.

> "On the server side, the same logic runs in `handleRRQ`. Line 78 uses `readNBytes` to read up to 512 bytes from the input stream, line 79 applies the same end-of-transfer rule, and line 81 calls the same `buildData` helper. Both sides share one packet-building module — no divergence."

**Cumulative time:** 2:15

---

### § 3. Initiating Requests — debugger walkthrough (2:15 → 3:30) — 5%

**Action:** Bring up the breakpoints view (`⌘⇧F8` on Mac) and enable ONLY these:
- `TftpUdpClient.java:88` (RRQ build)
- `TftpUdpClient.java:177` (WRQ build)
- `TftpUdpServer.java:73` (opcode read)
- `TftpUdpServer.java:84` (pool submit)

Click **Debug Run-Server**. Wait for "TFTP UDP Server listening on port 6969" in the console. *(~5 s)*

Edit Run-Client-GET-512's args if necessary to `get 512.txt`. Click **Debug Run-Client-GET-512**.

> "I've set breakpoints on the client's RRQ build and the server's opcode read. The client is launched with `get 512.txt`."

**Action:** The client hits line 88. The top of the Debug panel shows the current frame. Hover over `filename` in the editor — the tooltip shows `"512.txt"`.

> "We hit the client breakpoint. The `filename` variable is `512.txt`. Step over" — press **F8**.

**Action:** Execution passes line 88. In the Variables pane, expand `rrq`. Point at the array.

> "`rrq` is now a byte array: the first two bytes are zero-zero-one — big-endian opcode 1 for RRQ — then the ASCII `5-1-2-dot-t-x-t`, a zero byte, then `o-c-t-e-t`, and a final zero byte. That's exactly the RFC 1350 request format."

**Action:** Press **F9** (resume). Execution transfers to the server, which hits line 73 in `TftpUdpServer.java`.

> "Now control's on the server. It just received the datagram on the welcome socket."

**Action:** Press **F8** to step over line 73. Hover over `opcode` — shows `1`.

> "The `getOpcode` helper reads the first two bytes: value 1, so it's an RRQ."

**Action:** Press **F9**. Execution hits the server breakpoint on line 84.

> "Line 84: the server copies the request bytes and client address into a new `TftpTransferHandler` runnable, then hands it to a cached thread pool. The welcome socket is immediately free for the next client — that's how multiple simultaneous transfers work." *(Cumulative: ~3:05)*

**Action:** Press **F9** repeatedly until both debug sessions finish. Stop both sessions (red square).

**Action:** Edit the client run config program arguments to `put 512.txt`. Click **Debug Run-Server** again, then **Debug Run-Client-GET-512**.

> "Same flow for a WRQ — this time launching with `put 512.txt`."

**Action:** The client hits line 177. Hover over `filename` — `"512.txt"`. Press **F8**. In Variables, expand `wrq`: first two bytes are `00 02`.

> "Identical structure, but the opcode is 2 — WRQ. The server takes the same code path and dispatches a handler that expects DATA packets back."

**Action:** Press **F9** until completion. Stop both sessions.

**Cumulative time:** 3:30

---

### § 4. Error Handling — File not found (3:30 → 4:30) — 5%

**Action:** Disable previous breakpoints. Enable ONLY:
- `TftpTransferHandler.java:60` (file-existence check)
- `TftpUdpClient.java:125` (OP_ERROR branch)

Edit the client run config args to `get missing.txt`. **Debug Run-Server**, then **Debug Run-Client-GET-512**.

> "Now the error-handling case. I'm asking the server for `missing.txt`, which doesn't exist."

**Action:** The server hits line 60 inside `handleRRQ`.

> "The server's transfer handler resolves the filename against its base directory."

**Action:** In the Variables pane, right-click `filePath` → Evaluate Expression → type `Files.exists(filePath)` → Enter.

> "`Files.exists` returns false."

**Action:** Press **F8** once so execution enters the if-body. Press **F8** again so `sendError` is about to run.

> "We take the branch that builds an ERROR packet with code 1 and the message 'File not found', and sends it back to the client's address and port from the original RRQ."

**Action:** Press **F9** — the client breakpoint on line 125 fires.

> "The client received a packet, and `getOpcode` returned 5 — ERROR. Let me evaluate the error fields."

**Action:** In the editor, hover over the `pkt` variable in the `if (op == TftpPacket.OP_ERROR)` line. Right-click → Evaluate — type `TftpPacket.getErrorCode(pkt)` — result `1`. Then evaluate `TftpPacket.getErrorMessage(pkt)` — result `"File not found"`.

> "Error code 1, message 'File not found' — exactly what the RFC specifies. The client prints this to stderr and returns `RC_SERVER_ERROR`, which `main` hands to `System.exit` so the process exit code is 1."

**Action:** Press **F9** to finish. Stop both sessions.

**Cumulative time:** 4:30

---

### § 5. Completion Detection — end of a RRQ (4:30 → 5:45) — 5%

**Action:** Disable previous breakpoints. Enable ONLY:
- `TftpTransferHandler.java:79` (server's end-of-file flag)
- `TftpTransferHandler.java:107` (server's final-ACK match)
- `TftpUdpClient.java:145` (client's final-DATA detection)

Edit the client run config args back to `get 512.txt`. Make sure `client-cwd/512.txt` does not already exist — delete it if it does.

**Debug Run-Server**, then **Debug Run-Client-GET-512**.

> "I deliberately chose `512.txt`, which is exactly 512 bytes, because it's the edge case — an exact multiple of the block size forces a final zero-byte DATA packet to signal the end of transfer."

**Action:** The server hits line 79 on the **first** iteration.

> "First block. `bytesRead` is 512" — hover on `bytesRead` — "so `done` is false. The server sends DATA block 1 with a full 512-byte payload and waits for an ACK."

**Action:** Press **F9**. The client hits line 145.

> "Client received DATA block 1. `data.length` is 512, so this is NOT less than BLOCK_SIZE — the client doesn't break out of the loop. It sends ACK 1 and increments `expectedBlock` to 2."

**Action:** Press **F9**. The server hits line 79 again on the **second** iteration.

> "Back on the server. `readNBytes` returned 0 — end of file — so now `done` is true. The server still builds a DATA packet: block number 2, zero-byte payload. That empty DATA is the RFC-mandated end-of-transfer signal." *(Cumulative: ~5:00)*

**Action:** Press **F9**. The client hits line 145.

> "Client received DATA block 2. `data.length` is 0, which IS less than BLOCK_SIZE — this is how the client detects the final packet. It sends ACK 2, writes the full file to disk, and returns RC_OK."

**Action:** Press **F9**. The server hits line 107.

> "Server got back the client's final ACK. `getBlockNumber` equals `blockNum`, equal to 2 — match. It breaks out of the ACK loop."

**Action:** Press **F9** one more time. Execution returns to the outer `while(!done)`, sees `done = true` from earlier, and exits. The server prints "[RRQ] Complete: 512.txt" to the console.

> "Because `done` is already true from when we read zero bytes, the outer while-loop terminates cleanly. The server prints the completion message, the handler thread exits, and its ephemeral socket closes — leaving the welcome socket ready for the next client." *(Cumulative: ~5:35)*

**Action:** Show the Finder or IntelliJ project view — point at `client-cwd/512.txt` which now exists.

> "And the file has been written to the client's working directory. That's the full RRQ lifecycle end-to-end."

**Cumulative time:** 5:45

---

## 2. Post-recording checks

Before stopping the recording:
- Stop both debug sessions (two red squares).
- The total elapsed timer on screen should read between 5 min 30 s and 6 min. Re-shoot if under 4:30 or over 6:30.

After recording:
- Trim any dead air at the start/end so final clip is between 5:30 and 6:00.
- Export at 1080 p, H.264, MP4. Verify audio is clearly audible.
- Filename: `tftp-technical-walkthrough.mp4`.

---

## 3. Contingency notes

- **If IntelliJ stalls on `Debug` launch:** the port from a prior run is stuck. `lsof -i :6969` → kill the PID, retry.
- **If a breakpoint doesn't fire:** you forgot to tick "Enabled" in View Breakpoints for that specific line. Re-check before continuing.
- **If the client writes `512.txt` into the project root instead of `client-cwd`:** your run configuration's working directory wasn't `$PROJECT_DIR$/client-cwd`. Fix, delete the stray file, restart.
- **If you run over 7 minutes:** the marker stops at 7:00 exactly (per brief). Trim intro ("This is my TFTP implementation…") rather than cut any debugger step.
