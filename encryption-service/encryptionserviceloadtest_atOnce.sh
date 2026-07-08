#!/bin/bash

set -euo pipefail

############################################
# Configuration
############################################

# SOURCE_FILE="$HOME/testPTA_CBS_IFT_005.csv"\

SOURCE_FILE="$(pwd)/testPTA_CBS_IFT_005.csv"

# WORK_DIR="$HOME/encryption-load-test"

WORK_DIR="$(pwd)/encryption-load-test"

SFTP_HOST="localhost"
SFTP_PORT="2222"
SFTP_USER="testuser"
SFTP_PASS="testpass"
REMOTE_DIR="/upload/input"

TOTAL_FILES=2000

############################################

if [[ ! -f "$SOURCE_FILE" ]]; then
    echo "Source file not found:"
    echo "$SOURCE_FILE"
    exit 1
fi

if ! command -v sshpass >/dev/null 2>&1; then
    echo "Please install sshpass"
    echo "sudo apt install sshpass"
    exit 1
fi

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

echo "=========================================="
echo "Generating $TOTAL_FILES files..."
echo "=========================================="

for ((i=1; i<=TOTAL_FILES; i++))
do
    FILE_NAME=$(printf "testPTA_CBS_IFT_%07d.csv" "$i")
    cp "$SOURCE_FILE" "$WORK_DIR/$FILE_NAME"

    if (( i % 500 == 0 )); then
        echo "Generated $i files..."
    fi
done

echo
echo "Generation Complete."

############################################
# Create SFTP batch file
############################################

BATCH_FILE=$(mktemp)

for file in "$WORK_DIR"/*.csv
do
    echo "put $file $REMOTE_DIR/" >> "$BATCH_FILE"
done

echo "bye" >> "$BATCH_FILE"

############################################
# Upload
############################################

echo
echo "Uploading all files in a single SFTP session..."

sshpass -p "$SFTP_PASS" sftp \
    -oBatchMode=no \
    -oStrictHostKeyChecking=no \
    -P "$SFTP_PORT" \
    -b "$BATCH_FILE" \
    "$SFTP_USER@$SFTP_HOST"

echo
echo "Upload completed."

rm -f "$BATCH_FILE"

############################################
# Cleanup (optional)
############################################

echo "Removing local generated files..."

rm -rf "$WORK_DIR"

echo
echo "=========================================="
echo "Load Test Completed Successfully"
echo "Generated : $TOTAL_FILES files"
echo "Uploaded  : $TOTAL_FILES files"
echo "=========================================="
