# Encryption Service

Quarkus (JDK 21) service that watches an **SFTP input directory** for files,
ZIP-compresses each file into a standalone archive, PGP-encrypts that
archive (AES-256, integrity packet enabled), and writes the result to an
**output directory** on the same SFTP server as `<filename>.zip.pgp`.

## How it works

1. `FileProcessingService` polls the SFTP input directory on a cron schedule
   (`app.poll.cron`, default every 10s).
2. For every file found, it:
   - downloads the file (`SftpObjectService.downloadFromInput`)
   - wraps it into a standalone `.zip` archive (`ZipCompressionService.zip`)
   - PGP-encrypts the zip archive with the configured public key
     (`PgpEncryptionService.encrypt`)
   - uploads `<name>.zip.pgp` to the SFTP output directory
   - deletes the source file from the input directory so it isn't re-processed.

   e.g. `testing.csv` → `testing.csv.zip` (in memory) → `testing.csv.zip.pgp` (uploaded)

> Note: PGP itself also applies its own internal OpenPGP ZIP compression on
> whatever bytes it encrypts (per the original SOP). Since the payload is
> already a `.zip` at that point, this second compression pass won't shrink
> it much further — it's harmless, just slightly redundant. If you want to
> avoid double-compression, set `CompressionAlgorithmTags.UNCOMPRESSED` in
> `PgpEncryptionService` instead.

> Polling is simple and reliable for low-medium volume. For high volume or
> low latency, an event-driven trigger (e.g. an inotify watcher or a webhook
> from whatever drops files onto the SFTP server) could replace the
> `@Scheduled` poller — the encryption/upload logic underneath stays the same.

## Project layout

```
src/main/java/com/ember/
├── config/
│   └── PgpConfig.java              # type-safe mapping of pgp.* properties
├── storage/
│   └── FileStorageService.java     # backend interface (list/download/upload/delete)
├── sftp/
│   ├── SftpConfig.java             # type-safe mapping of sftp.* properties
│   └── SftpObjectService.java      # FileStorageService impl backed by SFTP (JSch)
├── compression/
│   └── ZipCompressionService.java  # wraps a file into a standalone .zip archive
├── encryption/
│   └── PgpEncryptionService.java   # Bouncy Castle OpenPGP encryption
└── service/
    └── FileProcessingService.java  # scheduled orchestrator (the "glue")
```

`FileProcessingService` depends only on the `FileStorageService` interface,
not on `SftpObjectService` directly — mainly so it's easy to swap in a mock
in tests (`@InjectMock FileStorageService`) without needing a real SFTP
connection.

## SFTP configuration

```properties
sftp.host=${SFTP_HOST:localhost}
sftp.port=${SFTP_PORT:2222}
sftp.username=${SFTP_USERNAME:testuser}
sftp.password=${SFTP_PASSWORD:testpass}
sftp.input-dir=${SFTP_INPUT_DIR:/upload/input}
sftp.output-dir=${SFTP_OUTPUT_DIR:/upload/output}
```

Defaults above match the local SFTP server in `docker-compose.yml`. For a
real server, override via env vars (`SFTP_HOST`, `SFTP_PORT`, etc.) or a
private key instead of a password — `SftpConfig` also supports
`sftp.private-key-path` for key-based auth.

## PGP key configuration (never hardcoded)

The public key is **never** bundled in the jar or committed to source.
Configured via:

| Variable                 | Description                                                                        |
|---------------------------|-------------------------------------------------------------------------------------|
| `PGP_PUBLIC_KEY_PATH`     | Filesystem path to public key `.asc` (preferred).                                   |
| `PGP_PUBLIC_KEY_CONTENT`  | Raw armored key text, if injecting directly as an env var/secret instead of a file. |
| `APP_POLL_CRON`           | Quartz cron expression for the poller (default `*/10 * * * * ?`).                   |

These are read via `.env` in dev mode (auto-loaded by Quarkus from the
project root) or real env vars in production.

**Local dev setup:**
```bash
cp .env.example .env   # .env is gitignored; .env.example is the committed template
```

**Key handling — important:**
- No classpath fallback — the app fails fast on startup with a clear error
  if neither `PGP_PUBLIC_KEY_PATH` nor `PGP_PUBLIC_KEY_CONTENT` is set.
- `secrets/` and `*.asc` are in `.gitignore` — anything you drop in
  `secrets/` for local testing stays out of git.
- In production, mount the key as a Kubernetes Secret volume / Docker
  secret and point `PGP_PUBLIC_KEY_PATH` at the mounted path.
- Never let the **private** key anywhere near this service — it only lives
  with whoever/whatever decrypts the files on the downstream side.

## Running locally

1. Start the local SFTP server:
   ```bash
   docker compose up -d
   ```
   This creates `./sftp-data/input` and `./sftp-data/output` on the host,
   bind-mounted into the SFTP container. The app auto-creates these
   directories on the SFTP side too (`FileStorageService.initialize()`), so
   no manual setup is needed there.

2. Copy the env template for the PGP key path:
   ```bash
   cp .env.example .env
   ```
3. Put your public key at `secrets/public-key.asc` (already gitignored,
   already referenced by `PGP_PUBLIC_KEY_PATH` in `.env.example`).
4. Run in dev mode:
   ```bash
   ./mvnw quarkus:dev
   ```
5. Drop a file into the input directory:
   ```bash
   cp testing.csv ./sftp-data/input/
   ```
   Within ~10s, `testing.csv.zip.pgp` should appear in `./sftp-data/output/`,
   and the source file should be gone from `./sftp-data/input/`.

## Testing decryption (sanity check)

To confirm the whole pipeline round-trips correctly, decrypt the output
with the matching private key, then unzip:

```bash
gpg --import private-key.asc
gpg --output testing.csv.zip --decrypt ./sftp-data/output/testing.csv.zip.pgp
unzip -p testing.csv.zip > testing.csv
cat testing.csv
```

## Failure behavior

- **Upload fails after encryption succeeds:** the source file is only
  deleted *after* a successful upload, so it's left in place in the input
  directory and retried on the next poll cycle. Nothing is lost — the
  already-encrypted bytes are just discarded from memory and re-produced
  on retry.
- **Encryption fails:** same as above — the source file stays untouched
  and is retried next cycle. Note there's currently no backoff or
  dead-letter handling, so a file that *always* fails (not a transient
  issue) will keep retrying and logging an error every poll cycle
  indefinitely.
- **Public key missing/invalid:** checked once at startup, not per file —
  the whole application fails to start rather than failing per file.

## Extending

- **Signing**: add a `PGPSignatureGenerator` step in `PgpEncryptionService`
  using a service-held private key before/with the literal data packet, if
  sender authentication is ever required downstream.
- **Large files**: current implementation buffers whole files in memory
  (fine for CSV-sized payloads). For large files, switch
  `SftpObjectService`/`PgpEncryptionService` to streaming — JSch's
  `ChannelSftp.get/put` and the PGP generator both support streaming; this
  is mostly a refactor of method signatures from `byte[]` to `InputStream`.
- **Event-driven instead of polling**: replace the `@Scheduled` poller with
  a filesystem watch (if the SFTP server's storage is locally accessible)
  or a webhook triggered by whatever process drops files onto the server.
