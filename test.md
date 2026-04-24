# TFTP Interoperability — Video Script

**Target runtime:** 1 min 45 s (of 2 min max = 87%).
**Coursework reference:** `brief.md` → "Interoperability Video (5%)", sub-tasks 1–2.
**Scope:** UDP implementation only (TCP interop is out of scope per RFC 1350).

**Interop partners:**
- **Third-party server** — macOS's built-in `tftpd` (BSD; `/usr/libexec/tftpd`, loaded via `launchctl`, listens on port 69, serves `/private/tftpboot`).
- **Third-party client** — macOS's built-in `/usr/bin/tftp` (BSD; interactive CLI).

Both are written in C and ship with Darwin — genuinely separate implementations from the Java code, which is what the RFC interop claim requires.

---

## 0. Pre-recording setup (do this BEFORE you hit record — not part of the 2 min)

### 0.1 Windows and layout
Open **Terminal.app** (or iTerm2). Create **two tabs**, both maximised side-by-side at 1080 p or better:

- **Tab A — "driver"** — this is where you type every command during the recording.
- **Tab B — "java-server"** — this is where the Java UDP server will run in §2.

In both tabs:
- Preferences → Profiles → Text: font size **18 pt** (minimum). Verify at 1080 p that every character is readable.
- `cd /Users/edwardbaker/Documents/networks-coursework` in both tabs.
- `clear` both tabs so the scrollback is empty before you hit record.

Open a **Finder** window. Navigate to `/Users/edwardbaker/Documents/networks-coursework/interop/` (you'll create this folder in §0.3). Leave Finder visible in a corner of the screen so the viewer can see files appearing.

### 0.2 Build the jars
From the repo root, run:

```
./setup.sh
```

This will (a) verify `test_image.jpg` is present, (b) build both UDP jars if missing. Confirm the final output says **"All checks passed"**. If it fails, fix before continuing.

The jars you need are:

- `TFTP-UDP-Server/target/TFTP-UDP-Server-1.0-SNAPSHOT.jar`
- `TFTP-UDP-Client/target/TFTP-UDP-Client-1.0-SNAPSHOT.jar`

### 0.3 Create the interop workspace
These four directories isolate the uploads and downloads so an MD5 comparison is honest (you never read a file from the same directory you wrote it to). Run this **exact** block in Tab A:

```
cd /Users/edwardbaker/Documents/networks-coursework
rm -rf interop
mkdir -p interop/client-up interop/client-dn interop/tftp-up interop/tftp-dn interop/server-cwd
cp test_image.jpg interop/client-up/
cp test_image.jpg interop/tftp-up/
ls -la interop/*/
```

The `ls` output must show `test_image.jpg` (636 KB-ish) in `client-up/` and `tftp-up/`, and the other three directories empty.

### 0.4 Pre-seed the Apple tftpd target file

Apple's `tftpd` **refuses to create new files** by default (the shipped `tftp.plist` does not use the `-w` flag). For a WRQ to succeed, the target file must already exist in `/private/tftpboot` and be world-writable. Pre-touch it now:

```
sudo -v
sudo rm -f /private/tftpboot/test_image.jpg
sudo touch /private/tftpboot/test_image.jpg
sudo chmod 666 /private/tftpboot/test_image.jpg
ls -la /private/tftpboot/test_image.jpg
```

The final `ls` must show mode `-rw-rw-rw-` and size `0`. The first `sudo -v` caches your password for 5 min — enough to cover the whole recording without any sudo prompt appearing mid-take.

### 0.5 Confirm the tftpd daemon is NOT currently loaded
It ships disabled, but a previous rehearsal may have loaded it. Run:

```
sudo launchctl list | grep tftp
```

If the output is empty → good, it's unloaded. If a line appears → unload it now:

```
sudo launchctl unload /System/Library/LaunchDaemons/tftp.plist
```

This guarantees the "load it" step during recording is visibly the action that enables it.

### 0.6 Record the source checksum
In Tab A:

```
md5 test_image.jpg
```

The output should be exactly:

```
MD5 (test_image.jpg) = c50827605162b9f8fc5db35b8040a460
```

This 32-char hash is the ground truth you will compare against four times during the video (once per transfer). If your `test_image.jpg` produces a different hash, you have the wrong file — stop and fix before recording.

### 0.7 Audio / screen / notifications
- Do Not Disturb **on**. Quit Slack, Mail, Discord, Teams.
- Mic check: record a 5-second clip, play back, verify levels.
- Screen recorder set to 1080 p, 30 fps. Capture the whole display (both tabs + Finder must be visible at once).
- Clear both terminal tabs (`clear` + Cmd-K) immediately before you hit record.

---

## 1. Recording — running script

> **Read the lines in quotes verbatim.** Everything outside quotes is an on-screen action. Timings are cumulative; the right-hand column is the target time when that line should END.

---

### § Intro (0:00 → 0:08)

**Action:** Tab A is focused, cursor at an empty prompt in the repo root. Finder visible showing `interop/` as set up in §0.3.

> "This is the TFTP interoperability demo. My Java UDP client and server, transferring `test_image.jpg` against macOS's built-in BSD `tftpd` and `tftp` — separate C implementations — to prove RFC 1350 conformance."

**Cumulative time:** 0:08

---

### § 1. My client ↔ Apple `tftpd` (0:08 → 0:58)

**Action:** Load the Apple daemon. In Tab A type:

```
sudo launchctl load -F /System/Library/LaunchDaemons/tftp.plist
sudo lsof -iUDP:69
```

> "Loading Apple's `tftpd` via `launchctl`. It now listens on UDP port 69 — you can see it here — and serves `/private/tftpboot`."

The `lsof` output must show `tftpd` with `UDP *:tftp`. *(Cumulative: ~0:15)*

**Action:** WRQ (my client writes **to** Apple's server). In Tab A:

```
cd interop/client-up
java -jar ../../TFTP-UDP-Client/target/TFTP-UDP-Client-1.0-SNAPSHOT.jar put test_image.jpg 127.0.0.1 69
```

> "My client uploads `test_image.jpg` to Apple's `tftpd` on port 69. That's a WRQ — opcode 2 — then 1,241 full 512-byte DATA packets and a 453-byte final block, each ACKed."

**Action:** While the command completes (sub-second on localhost), immediately run:

```
md5 /private/tftpboot/test_image.jpg
```

> "The file Apple's daemon received has MD5 `c50827…460` — byte-identical to the source." *(Cumulative: ~0:30)*

**Action:** RRQ (my client reads **from** Apple's server) into a clean directory:

```
cd ../client-dn
java -jar ../../TFTP-UDP-Client/target/TFTP-UDP-Client-1.0-SNAPSHOT.jar get test_image.jpg 127.0.0.1 69
md5 test_image.jpg
```

> "And reading it back — RRQ, opcode 1 — from Apple's daemon into an empty directory. Same MD5 — no corruption in either direction."

**Action:** Visual verification:

```
open test_image.jpg
```

Preview launches showing the photograph. Hold the Preview window on screen for ~3 seconds, then `Cmd-W` to close it. *(Cumulative: ~0:55)*

> "Opening it in Preview — image renders cleanly. My client is interop-correct against a third-party server."

**Action:** Unload the Apple daemon so port 69 is freed and the next half is unambiguously our server:

```
sudo launchctl unload /System/Library/LaunchDaemons/tftp.plist
```

**Cumulative time:** 0:58

---

### § 2. Apple `tftp` client ↔ my Java server (0:58 → 1:45)

**Action:** Switch to **Tab B**. Start the Java server from `interop/server-cwd/` so it serves an initially empty directory:

```
cd /Users/edwardbaker/Documents/networks-coursework/interop/server-cwd
java -jar ../../TFTP-UDP-Server/target/TFTP-UDP-Server-1.0-SNAPSHOT.jar 6969
```

Wait for the log line `TFTP UDP Server listening on port 6969`. *(Cumulative: ~1:05)*

> "My Java server, listening on 6969, serving an empty directory."

**Action:** Switch back to **Tab A**. WRQ from the Apple tftp client. Type each line and press **Enter** — the `tftp>` prompt is interactive:

```
cd /Users/edwardbaker/Documents/networks-coursework/interop/tftp-up
tftp 127.0.0.1 6969
mode binary
put test_image.jpg
quit
```

> "Apple's `tftp` client — `mode binary` is octet mode — uploading to my server. The transfer status line confirms 635 845 bytes sent."

**Action:** Immediately verify server-side delivery (still in Tab A):

```
md5 ../server-cwd/test_image.jpg
```

> "My server received the full file, MD5 unchanged." *(Cumulative: ~1:25)*

**Action:** RRQ from the Apple tftp client into a clean directory:

```
cd ../tftp-dn
tftp 127.0.0.1 6969
mode binary
get test_image.jpg
quit
md5 test_image.jpg
```

> "And Apple's `tftp` reading back — octet mode again — lands here. Identical MD5."

**Action:** Visual verification:

```
open test_image.jpg
```

Preview launches. Hold for ~3 seconds, then `Cmd-W`. *(Cumulative: ~1:42)*

> "Preview again — image is clean. Both halves of the interop claim verified: my client talks to Apple's server, Apple's client talks to my server, round-trip, RFC 1350 octet mode."

**Cumulative time:** 1:45

---

## 2. Post-recording checks

Before stopping the recording:
- Tab B still shows the Java server's transfer logs (WRQ + RRQ completion lines) — leave those visible for 1 second at the end.
- On-screen timer should read between 1:35 and 1:55. Re-shoot if under 1:20 or over 1:55.

Stop recording. Then:
- Trim any dead air at start/end — final clip must be ≤ 2:00.
- Export at 1080 p, H.264, MP4.
- Filename: `tftp-interoperability.mp4`.

Tear-down (not on camera, but do it so your machine is clean):

```
# Tab B: Ctrl-C to stop the Java server.
# Tab A:
sudo launchctl unload /System/Library/LaunchDaemons/tftp.plist 2>/dev/null
sudo rm -f /private/tftpboot/test_image.jpg
cd /Users/edwardbaker/Documents/networks-coursework
rm -rf interop
```

---

## 3. Contingency notes

- **`sudo` prompts you for a password mid-recording.** You forgot §0.4's `sudo -v`. Stop, rehearse the `sudo -v` step within 5 minutes of the next take, re-shoot.

- **`launchctl load` fails with "service already loaded".** A previous run didn't unload cleanly. `sudo launchctl unload /System/Library/LaunchDaemons/tftp.plist`, then retry. Redo the take — the viewer must see a clean load.

- **`lsof -iUDP:69` shows nothing after the load.** SIP or a macOS update has disabled inetd-compat daemons on your release. Fallback: edit `/System/Library/LaunchDaemons/tftp.plist` to add a `<string>-w</string>` arg and `Disabled: false`, then `sudo launchctl bootstrap system ...`. If this path is needed, rehearse it off-camera first — troubleshooting system daemons on camera will blow the 2-minute cap.

- **Apple's `tftpd` rejects my client's PUT with "Access violation".** You skipped §0.4, or `/private/tftpboot/test_image.jpg` isn't mode 666. Re-run §0.4 and retry. Apple's `tftpd` will not create a new file on WRQ — only overwrite an existing world-writable one.

- **`tftp>` client hangs on `put` / `get`.** The Java server isn't up, or port 6969 is already bound by a zombie JVM. `lsof -i :6969` in Tab A, kill the PID, restart the server in Tab B.

- **`tftp>` returns a tiny file (0 bytes, or 13 bytes for a JPEG).** You forgot `mode binary`. The default mode is `netascii`, which mangles non-text octets. Always issue `mode binary` first.

- **Preview shows a grey/torn image but MD5 matches.** Impossible in practice — if MD5s match, the bytes are identical. If Preview genuinely misrenders, your `test_image.jpg` in the repo root is bad; re-verify §0.6's hash against `c50827605162b9f8fc5db35b8040a460`.

- **You run over 2 minutes.** The marker stops at 2:00 exactly (per brief). Trim the intro sentence first, then cut `lsof -iUDP:69` and one of the MD5 lines. Do **not** cut either `open` step — the visual check is explicitly required by the brief.
