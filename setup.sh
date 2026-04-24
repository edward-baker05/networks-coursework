#!/usr/bin/env bash
# Pre-recording setup for the TFTP demo video.
# Run once before ./demo.sh.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

FAIL=0

ok()   { printf "  \033[32m✓\033[0m  %s\n" "$1"; }
fail() { printf "  \033[31m✗\033[0m  %s\n" "$1"; ((FAIL++)) || true; }

printf "\n  TFTP Demo — Pre-Recording Setup\n"
printf "  ──────────────────────────────────\n\n"

# Create client working directory and remove any stale download
mkdir -p client-cwd
rm -f client-cwd/512.txt
ok "client-cwd/ ready (stale 512.txt cleared)"

# Required test files
for f in 512.txt medium.txt large.txt test_image.jpg; do
    if [[ -f "$f" ]]; then
        ok "$f present"
    else
        fail "$f MISSING from repo root"
    fi
done

# Port 6969
if lsof -i :6969 2>/dev/null | grep -q LISTEN; then
    fail "port 6969 is in use — kill the process first:"
    lsof -i :6969
else
    ok "port 6969 is free"
fi

# Maven jars
SERVER_JAR="TFTP-UDP-Server/target/TFTP-UDP-Server-1.0-SNAPSHOT.jar"
CLIENT_JAR="TFTP-UDP-Client/target/TFTP-UDP-Client-1.0-SNAPSHOT.jar"

if [[ ! -f "$SERVER_JAR" || ! -f "$CLIENT_JAR" ]]; then
    printf "\n  Building Maven projects (may take ~30 s)...\n"
    (cd TFTP-UDP-Server && mvn package -q -DskipTests) \
    && (cd TFTP-UDP-Client && mvn package -q -DskipTests) \
    && ok "jars built" \
    || fail "Maven build failed"
else
    ok "jars already built"
fi

printf "\n"
if [[ "$FAIL" -gt 0 ]]; then
    printf "  \033[31m✗ %d check(s) failed — fix before recording.\033[0m\n\n" "$FAIL"
    exit 1
else
    printf "  \033[32m✓ All checks passed — ready to record.\033[0m\n"
    printf "  Next: ./demo.sh\n\n"
fi
