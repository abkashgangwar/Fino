# Encryption Service

Quarkus (JDK 21) service that watches an **SFTP input directory** (on one
server) for files, ZIP-compresses each file into a standalone archive,
PGP-encrypts that archive (AES-256, integrity packet enabled), and writes the
result to an **output directory on a separate SFTP server** as
`<filename>.zip.pgp`.

## How it works

1. `FileProcessingService` polls the SFTP input directory on a cron schedule
   (`app.poll.cron`, default every 10 seconds), with `concurrentExecution = SKIP`
   so a slow poll cycle over a big backlog can never overlap with the next tick.
2. Each poll cycle processes up to `app.processing.parallelism` files concurrently
   (default 8). For every file:
   - downloads the file straight to a local temp file (`SftpObjectService.downloadFromInput`)
   - wraps it into a standalone `.zip` archive, streamed file-to-file (`ZipCompressionService.zip`)
   - PGP-encrypts the zip archive with the configured public key, streamed file-to-file
     (`PgpEncryptionService.encrypt`)
   - uploads `<name>.zip.pgp` to the SFTP output directory, streamed from the temp file
   - **moves the source file into `<input-dir>/done/`** - it is never deleted, only
     relocated, once its encrypted output is safely uploaded.

   e.g. `testing.csv` → `testing.csv.zip` (temp file) → `testing.csv.zip.pgp` (uploaded),
   then `testing.csv` → `done/testing.csv` (renamed in place).

   Every stage streams through fixed-size buffers rather than buffering the whole
   file in a `byte[]`, so file size no longer drives JVM heap usage - only local
   disk space in the OS temp directory, which is cleaned up per file as soon as
   it's processed.

> **Idempotency, since input files are never deleted:** a fully-processed source
> file is renamed into an input-side `done/` sub-directory (`FileStorageService.moveToDone`)
> right after its output uploads successfully. `listInputObjects()` lists the input
> directory non-recursively, so anything already in `done/` simply never appears in
> future polls again - no output-directory lookup needed per file.
>
> This replaced an earlier approach that checked whether `<name>.zip.pgp` already
> existed in the output directory (`existsInProcessed`) for every input file, every
> poll cycle. That's a single SFTP round-trip per file - fine at low volume, but
> since input files are never deleted, the backlog of "already done, just checking"
> files only ever grew. At 100,000+ files that alone could add minutes of pure
> existence-checking to every cycle, before any real work even started. Moving a
> file out of the pending listing instead of just checking it against the output
> means each poll only ever sees genuinely-pending work - the check overhead no
> longer grows with how many files have been processed over the service's lifetime.

> Note: PGP itself also applies its own internal OpenPGP ZIP compression on
> whatever bytes it encrypts (per the original SOP). Since the payload is
> already a `.zip` at that point, this second compression pass won't shrink
> it much further — it's a real (if modest) CPU cost with no size benefit at
> volume, but it's left enabled intentionally here since `pgp.compression=ZIP`
> is an SOP-mandated setting, not just a technical default. If that constraint
> ever changes, switch it to `CompressionAlgorithmTags.UNCOMPRESSED` in
> `PgpEncryptionService` to skip the redundant pass.

> Polling is simple and reliable for low-medium volume. For high volume or
> low latency, an event-driven trigger (e.g. an inotify watcher or a webhook
> from whatever drops files onto the SFTP server) could replace the
> `@Scheduled` poller — the encryption/upload logic underneath stays the same.

## Why the service was falling over under load (2000 files / 100k rows)

Two compounding bugs, both fixed above:

1. **Overlapping poll cycles racing the same SFTP channel.** The old
   `@Scheduled` annotation never set `concurrentExecution`, so it defaulted to
   Quarkus's `PROCEED` (overlapping runs allowed) despite a comment in the code
   claiming otherwise. A poll cycle over a large backlog easily runs longer than
   the cron interval, so the next tick started a second `pollAndProcess()` call
   while the first was still running - and JSch's `ChannelSftp` is **not**
   thread-safe, so two calls sharing one channel corrupted its state. Fixed with
   `concurrentExecution = Scheduled.ConcurrentExecution.SKIP` plus a per-poll
   SFTP connection pool so parallel workers never share a channel either.
2. **Everything buffered fully in memory.** `download → zip → encrypt` each
   produced a brand-new full-size `byte[]`, so a single large file could be
   resident in heap 3-4 times over, with no `-Xmx` tuning to absorb it. At
   2000 files this reliably exhausted the heap. Fixed by staging each file
   through local temp files and streaming every stage (SFTP transfer, ZIP,
   PGP) via fixed 64KB buffers instead.

## Scaling further: 100,000+ files

The two fixes above make a single poll cycle safe and heap-independent, but at
much larger backlogs (e.g. 100,000 files) a third issue shows up: the
idempotency check itself.

- **Old behavior:** every input file, every poll cycle, triggered an SFTP
  `lstat` against the output directory (`existsInProcessed`) just to confirm
  "have I already done this one?" Since input files are never deleted, that
  backlog of already-processed-but-still-listed files only ever grew. At
  100,000 files this alone could add minutes of pure existence-checking to
  every cycle, before any real work started - and it would only get worse
  over the service's lifetime.
- **Fixed by:** moving a file into an input-side `done/` sub-directory
  (`FileStorageService.moveToDone`, a metadata-only SFTP rename) right after
  its output is successfully uploaded, instead of just checking it against the
  output. `listInputObjects()` is a non-recursive listing of the input
  directory, so anything already in `done/` never appears in it again. Each
  poll cycle now only ever sees genuinely-pending work - the per-cycle listing
  cost is bounded by the current backlog, not by how many files have *ever*
  been processed.

One resulting batch-size nuance worth knowing: `pollAndProcess()` submits the
*entire* pending list to the worker pool and blocks (`future.get()` in a loop)
until every one of them finishes before returning - which is what lets
`concurrentExecution = SKIP` guarantee no overlap. So a single very large batch
(e.g. all 100,000 files pending at once) runs as one long cycle bounded by
`app.processing.parallelism`, not `app.poll.cron` - the cron interval only
governs the gap *between* cycles, not how long one cycle is allowed to take.
Any file still mid-flight (or not yet picked up) if the process crashes simply
stays visible to the next cycle's `listInputObjects()` call, since it's only
moved to `done/` after a fully successful upload - so at most the one file that
was actually in flight gets reprocessed, nothing is ever lost or silently
skipped.

## Project layout

```
src/main/java/com/ember/
├── config/
│   └── PgpConfig.java              # type-safe mapping of pgp.* properties
├── storage/
│   └── FileStorageService.java     # backend interface (list/download/upload/moveToDone/delete)
├── sftp/
│   ├── SftpEndpointConfig.java     # connection fields shared by both SFTP servers below
│   ├── SftpInputConfig.java        # type-safe mapping of sftp.input.* properties
│   ├── SftpOutputConfig.java       # type-safe mapping of sftp.output.* properties
│   └── SftpObjectService.java      # FileStorageService impl backed by SFTP (JSch) - talks to both servers
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

Input and output are two **independent SFTP servers**, each with its own config
prefix - this matters when the upstream drop server and the destination server
for encrypted output are genuinely different machines/vendors, not just
different directories on the same box.

```properties
# Server files are picked up FROM
sftp.input.host=localhost
sftp.input.port=2222
sftp.input.username=testuser
sftp.input.password=testpass
sftp.input.dir=/upload/input
sftp.input.pool-size=8
sftp.input.bulk-requests=64

# Server encrypted output is written TO
sftp.output.host=localhost
sftp.output.port=2223
sftp.output.username=testuser2
sftp.output.password=testpass2
sftp.output.dir=/upload/output
sftp.output.pool-size=8
sftp.output.bulk-requests=64
```

`sftp.input.pool-size` / `sftp.output.pool-size` control how many concurrent SFTP
session/channel pairs are kept ready for use against each server - keep BOTH
`>= app.processing.parallelism` (see below) so every concurrent file-processing
worker can always get one connection of each kind.

`sftp.input.bulk-requests` / `sftp.output.bulk-requests` control JSch's
request-pipelining depth per channel (how many SFTP read/write requests can be
in flight, unacknowledged, at once). JSch's own default is a conservative 16 -
fine on localhost, but on any link with real round-trip latency that caps
large-file transfer throughput well below what the link can actually do, since
each in-flight slot is what lets the next chunk go out before the previous
one's ack comes back. 64 here is a reasonable starting point for large files at
volume; lower it if a particular SFTP server pushes back on high concurrency.

## Processing configuration

```properties
app.poll.cron=*/10 * * * * ?
app.processing.parallelism=8
```

`app.processing.parallelism` is how many files are encrypted/uploaded concurrently
within a single poll cycle. Raise it (together with both `sftp.input.pool-size`
and `sftp.output.pool-size`) to push through a large backlog faster; each worker
holds only small fixed-size buffers in memory, not full file contents, so
raising this scales with CPU/network capacity rather than heap.

Defaults above match the two local SFTP containers in `docker-compose.yml`
(`sftp-input` on port 2222, `sftp-output` on port 2223). For real servers, just
edit the values directly - or use a private key instead of a password, via
`sftp.input.private-key-path` / `sftp.output.private-key-path`
(`SftpInputConfig` / `SftpOutputConfig` both support this, inherited from the
shared `SftpEndpointConfig`).

**Tuning for a very large backlog (e.g. 100,000+ files):** the levers that
actually move the needle are `app.processing.parallelism` and BOTH
`sftp.input.pool-size` / `sftp.output.pool-size` (always raise all three
together - each pool must have at least as many connections as there are
concurrent workers). The defaults (8/8/8) are deliberately conservative so the
service is safe out of the box; the right higher value depends on your real
SFTP servers' concurrent-session limits, network bandwidth, and available CPU
(PGP/AES-256 encryption is CPU-bound). There's no single safe number to
recommend blindly - raise all three gradually (e.g. 8 → 16 → 32) while watching
CPU, network throughput, and both SFTP servers' own load, rather than jumping
straight to a large value.

## PGP key configuration (never hardcoded)

The public key is **never** bundled in the jar or committed to source.
Configured via:

| Variable                 | Description                                                                        |
|---------------------------|-------------------------------------------------------------------------------------|
| `PGP_PUBLIC_KEY_PATH`     | Filesystem path to public key `.asc` (preferred).                                   |
| `PGP_PUBLIC_KEY_CONTENT`  | Raw armored key text, if injecting directly as an env var/secret instead of a file. |
| `APP_POLL_CRON`           | Quartz cron expression for the poller (default `0 */5 * * * ?`, i.e. every 5 minutes). |

These are read via `.env` in dev mode (auto-loaded by Quarkus from the
project root) or real env vars in production.

**Local dev setup:**
```bash
cp .env.example .env   # .env is gitignored; .env.example is the committed template
```

> ⚠️ **Before running:** `.env.example` only contains placeholder values.
> Ask the project/service owner for the actual `.env` content (real PGP key
> path/content, SFTP credentials, etc.) and fill it into your local `.env`
> — never commit the real `.env` back to source control.

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

1. Start the local SFTP servers (input + output, two separate containers):
   ```bash
   docker compose up -d
   ```
   This creates `./sftp-data/input` and `./sftp-data/output` on the host,
   bind-mounted into the `sftp-input` and `sftp-output` containers
   respectively. The app auto-creates the matching directories on each SFTP
   server too (`FileStorageService.initialize()`), so no manual setup is
   needed there.

2. Copy the env template for the PGP key path:
   ```bash
   cp .env.example .env
   ```
   > Then ask the owner for the actual `.env` values (this template only
   > has placeholders) and update your local `.env` accordingly.
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
   Within ~5 minutes, `testing.csv.zip.pgp` should appear in `./sftp-data/output/`,
   and `testing.csv` should be moved from `./sftp-data/input/` into
   `./sftp-data/input/done/` (not deleted).

## Testing decryption (sanity check)

To confirm the whole pipeline round-trips correctly, decrypt the output
with the matching private key, then unzip:

```bash
gpg --import private-key.asc
gpg --output testing.csv.zip --decrypt ./sftp-data/output/testing.csv.zip.pgp
unzip -p testing.csv.zip > testing.csv
cat testing.csv
```

## Functional tests (Robot Framework)

`src/test/robot/` has an end-to-end functional suite that starts the real
packaged service with a dedicated **`test` Quarkus profile** active
(`%test.*` properties at the bottom of `application.properties` - own poll
cadence, own HTTP port, etc.), drops a file into the SFTP input directory,
and asserts the encrypted output lands in the output directory with the
source moved to `done/`. See `src/test/robot/README.md` for setup and how
to run it.


## Failure behavior

- **Source files are never deleted.** They stay in the input directory
  indefinitely for audit/retry purposes - either directly in the input
  directory (still pending) or under `done/` (fully processed). The move
  into `done/` (see above) is what stops a successfully-processed file from
  being re-encrypted on the next poll — it does *not* stop retries for a
  file that hasn't produced output yet, since it only happens after upload
  succeeds.
- **Upload or encryption fails:** since nothing is deleted, the file is
  automatically retried on the next poll cycle. There's deliberately no
  in-cycle retry/backoff for a single file: at this scale, a systemic issue
  (e.g. the SFTP server going unreachable) would hit many files at once, and
  retrying each one individually - each with its own connection-timeout wait -
  would multiply how long the *whole* batch takes to fail through, which
  under `concurrentExecution = SKIP` directly delays how soon the next poll
  cycle (a cheaper, natural retry) can even start. Failing fast per file and
  letting the next cycle handle it is the safer default at this scale. There's
  also no cross-cycle backoff or dead-letter handling, so a file that
  *always* fails (not a transient issue) will keep retrying and logging an
  error every poll cycle indefinitely.
- **Public key missing/invalid:** checked once at startup, not per file —
  the whole application fails to start rather than failing per file.
- **SFTP pool exhausted:** if all connections in either pool are busy for
  more than 30s, a borrow times out with a clear `IllegalStateException`
  naming which side (input/output) and which property to raise -
  `sftp.input.pool-size` or `sftp.output.pool-size` - rather than hanging forever.

## Extending

- **Signing**: add a `PGPSignatureGenerator` step in `PgpEncryptionService`
  using a service-held private key before/with the literal data packet, if
  sender authentication is ever required downstream.
- **Retention/cleanup of old `done/` files**: processed files are kept
  forever under `done/`, not deleted. If that directory grows large enough
  to matter (millions of entries over the service's lifetime), consider a
  separate scheduled job that purges `done/` entries older than N days —
  `FileStorageService.deleteFromInput` is still on the interface for exactly
  this kind of use, just no longer called from the main pipeline.
- **Sharding `done/` (and `input/`) by date**: if a single flat directory
  ever grows large enough that even a non-recursive `ls` gets slow on your
  SFTP server, consider partitioning by date (e.g. `input/2026/07/04/`) so
  no single directory's listing grows unbounded.
- **Event-driven instead of polling**: replace the `@Scheduled` poller with
  a filesystem watch (if the SFTP server's storage is locally accessible)
  or a webhook triggered by whatever process drops files onto the server.
