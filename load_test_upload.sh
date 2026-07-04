#!/usr/bin/env bash
# load_test_upload.sh
#
# Uploads the SAME source file N times (with unique names) into the
# encryption service's SFTP input dir, to stress-test it.
#
# Two modes:
#   local  - copies straight into the bind-mounted ./sftp-data/input folder.
#            Fast, no extra tools needed. Only works when you're running the
#            local docker-compose SFTP server on THIS host (bind mount).
#   sftp   - uploads over the real SFTP protocol in one batched session.
#            Works against any SFTP server (local or remote/UAT).
#            Needs `sshpass` installed for password auth.
#
# Usage examples:
#   ./load_test_upload.sh --file testing.csv --count 1000 --mode local
#   ./load_test_upload.sh --file testing.csv --count 1000 --mode sftp \
#       --host uat.example.com --port 22 --user svc_user --pass secret \
#       --input-dir /upload/input
#
# Every uploaded file is named: <prefix>_<basename>_<seq>.<ext>
# so you can later grep/count only this run's files (e.g. "loadtest_").

set -euo pipefail

SOURCE_FILE=""
COUNT=1000
MODE="local"
PREFIX="loadtest"

SFTP_HOST="localhost"
SFTP_PORT=2222
SFTP_USER="testuser"
SFTP_PASS="testpass"
SFTP_INPUT_DIR="/upload/input"

LOCAL_INPUT_DIR="./sftp-data/input"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file) SOURCE_FILE="$2"; shift 2 ;;
    --count) COUNT="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --host) SFTP_HOST="$2"; shift 2 ;;
    --port) SFTP_PORT="$2"; shift 2 ;;
    --user) SFTP_USER="$2"; shift 2 ;;
    --pass) SFTP_PASS="$2"; shift 2 ;;
    --input-dir) SFTP_INPUT_DIR="$2"; shift 2 ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ -z "$SOURCE_FILE" || ! -f "$SOURCE_FILE" ]]; then
  echo "Error: --file <path> is required and must point to an existing file."
  exit 1
fi

EXT="${SOURCE_FILE##*.}"
BASENAME="$(basename "$SOURCE_FILE" ".$EXT")"

echo "Mode:    $MODE"
echo "Source:  $SOURCE_FILE"
echo "Count:   $COUNT"
echo "Prefix:  $PREFIX"
echo "Uploading..."
START=$(date +%s)

if [[ "$MODE" == "local" ]]; then
  mkdir -p "$LOCAL_INPUT_DIR"
  for i in $(seq -w 1 "$COUNT"); do
    cp "$SOURCE_FILE" "$LOCAL_INPUT_DIR/${PREFIX}_${BASENAME}_${i}.${EXT}"
  done

elif [[ "$MODE" == "sftp" ]]; then
  command -v sshpass >/dev/null 2>&1 || {
    echo "Error: sshpass not found (needed for password-auth batch upload)."
    echo "Install it (e.g. 'sudo apt install sshpass') or use --mode local."
    exit 1
  }
  BATCH_FILE="$(mktemp)"
  for i in $(seq -w 1 "$COUNT"); do
    echo "put \"$SOURCE_FILE\" \"$SFTP_INPUT_DIR/${PREFIX}_${BASENAME}_${i}.${EXT}\"" >> "$BATCH_FILE"
  done
  sshpass -p "$SFTP_PASS" sftp -oStrictHostKeyChecking=no -oBatchMode=no \
    -P "$SFTP_PORT" -b "$BATCH_FILE" "$SFTP_USER@$SFTP_HOST"
  rm -f "$BATCH_FILE"

else
  echo "Unknown mode: $MODE (use 'local' or 'sftp')"
  exit 1
fi

END=$(date +%s)
echo ""
echo "Done. Uploaded $COUNT files in $((END - START))s."
echo ""
echo "Now run check_load_test.sh to see how the service handled them, e.g.:"
echo "  ./check_load_test.sh --count $COUNT --prefix $PREFIX --mode $MODE"
