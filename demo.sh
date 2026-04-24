#!/usr/bin/env bash
# TFTP recording driver.
#
# Usage:
#   ./demo.sh              — create tmux session and attach
#   ./demo.sh --conductor  — step-through loop (called automatically inside the session)

SESSION="tftp-demo"
ROOT="$(cd "$(dirname "$0")" && pwd)"

SERVER_JAR="$ROOT/TFTP-UDP-Server/target/TFTP-UDP-Server-1.0-SNAPSHOT.jar"
CLIENT_JAR="$ROOT/TFTP-UDP-Client/target/TFTP-UDP-Client-1.0-SNAPSHOT.jar"

PACKET="$ROOT/TFTP-UDP-Server/src/main/java/tftp/udp/server/TftpPacket.java"
CLIENT_SRC="$ROOT/TFTP-UDP-Client/src/main/java/tftp/udp/client/TftpUdpClient.java"
HANDLER="$ROOT/TFTP-UDP-Server/src/main/java/tftp/udp/server/TftpTransferHandler.java"

# Pane layout (created in setup_session below):
#   pane 0  top-left      nvim
#   pane 1  bottom        conductor (this script, --conductor mode)
#   pane 2  top-right-top server terminal
#   pane 3  top-right-bot client terminal
NVIM_PANE="$SESSION:0.0"
CTRL_PANE="$SESSION:0.1"
SERVER_PANE="$SESSION:0.2"
CLIENT_PANE="$SESSION:0.3"

# ─── Conductor helpers ────────────────────────────────────────────────────────

goto() {
    local file=$1 line=$2
    # Ensure normal mode, then open file at line and centre the view
    tmux send-keys -t "$NVIM_PANE" "Escape"
    sleep 0.1
    tmux send-keys -t "$NVIM_PANE" ":e +${line} ${file}" Enter
    sleep 0.4
    tmux send-keys -t "$NVIM_PANE" "zz" ""
}

step() {
    printf "\n  \033[1;33m→\033[0m  %s\n" "$1"
    read -rp "    [Enter] to advance... " _
}

# ─── Conductor (runs inside pane 1) ──────────────────────────────────────────

conductor() {
    clear
    printf "\033[1m  TFTP Demo — Recording Driver\033[0m\n"
    printf "  Sections 1–2: use [Enter] to navigate nvim above.\n"
    printf "  Sections 3–5: switch to IntelliJ for the debugger.\n\n"

    # § 1 — Packet Header
    step "§ 1a  Opcode constants (TftpPacket.java:18)"
    goto "$PACKET" 18

    step "§ 1b  buildRequest — opcode + filename + mode (TftpPacket.java:45)"
    goto "$PACKET" 45

    step "§ 1c  buildData / buildAck — DATA and ACK headers (TftpPacket.java:68)"
    goto "$PACKET" 68

    step "§ 1d  buildError — error code + message (TftpPacket.java:89)"
    goto "$PACKET" 89

    # § 2 — Packetisation
    step "§ 2a  doPut chunking loop — Math.min(512, remaining) (TftpUdpClient.java:220)"
    goto "$CLIENT_SRC" 220

    step "§ 2b  handleRRQ read loop — readNBytes + done flag (TftpTransferHandler.java:74)"
    goto "$HANDLER" 74

    printf "\n  \033[1;32m✓ Sections 1–2 done.\033[0m\n"
    printf "  Switch to IntelliJ for §§ 3–5 (debugger walkthrough).\n\n"
    printf "  Server pane and client pane are ready if you want a quick live run:\n"
    printf "    server:  java -jar %s\n" "$(basename "$SERVER_JAR")"
    printf "    client:  java -jar %s get 512.txt\n\n" "$(basename "$CLIENT_JAR")"
}

if [[ "${1:-}" == "--conductor" ]]; then
    conductor
    exit 0
fi

# ─── Session setup ────────────────────────────────────────────────────────────

if tmux has-session -t "$SESSION" 2>/dev/null; then
    printf "Session '%s' already exists. Kill and recreate? [y/N] " "$SESSION"
    read -r answer
    if [[ "$answer" =~ ^[Yy]$ ]]; then
        tmux kill-session -t "$SESSION"
    else
        printf "Attaching to existing session.\n"
        tmux attach-session -t "$SESSION"
        exit 0
    fi
fi

# Pane 0: full window
tmux new-session -d -s "$SESSION"

# Pane 1: conductor strip at bottom (22% of height)
tmux split-window -v -p 22 -t "$SESSION:0.0"

# Pane 2: server on right of nvim area (35% of remaining top width)
tmux split-window -h -p 35 -t "$SESSION:0.0"

# Pane 3: client below server (50% of right column)
tmux split-window -v -p 50 -t "$SESSION:0.2"

# Start nvim in left pane (pane 0)
tmux send-keys -t "$NVIM_PANE" \
    "nvim -c 'set number' -c 'set cursorline' '$PACKET'" Enter

# Prepare server pane (pane 2)
tmux send-keys -t "$SERVER_PANE" "cd '$ROOT' && clear" Enter

# Prepare client pane (pane 3) — working dir is client-cwd so downloads land here
tmux send-keys -t "$CLIENT_PANE" "cd '$ROOT/client-cwd' && clear" Enter

# Give nvim a moment to load, then start conductor in pane 1
sleep 0.8
tmux send-keys -t "$CTRL_PANE" "cd '$ROOT' && bash demo.sh --conductor" Enter

# Focus conductor pane so keyboard input goes there on attach
tmux select-pane -t "$CTRL_PANE"
tmux attach-session -t "$SESSION"
