# Functional test suite (Robot Framework)

Black-box, end-to-end test for `ember-encryption-service`. It starts the real
packaged service with the **`test` Quarkus profile** active (see the
`%test.*` block at the bottom of `src/main/resources/application.properties`),
drops a plain file into the SFTP input directory, and asserts on exactly what
a human would check by hand:

1. `<name>.zip.pgp` appears in the SFTP output directory, non-empty.
2. The original source file is moved into `input/done/`.

It does not touch any Java internals - only SFTP and the process's own log
output - so it exercises the service the same way a real upstream system does.

## Layout

```
src/test/robot/
├── functional_encryption_pipeline.robot   # the test suite
├── resources/
│   └── common.resource                    # shared keywords/variables (start/stop
│                                           # service, SFTP helpers)
├── testdata/
│   └── sample_input.csv                   # small file uploaded by the test
├── requirements.txt
└── README.md                              # this file
```

## One-time setup

```bash
# 1. Python deps for Robot Framework + its SSH library
pip install -r src/test/robot/requirements.txt

# 2. Local test SFTP servers - input AND output are now two separate containers
#    (same ones used for manual/dev testing)
docker compose up -d

# 3. Package the service - the suite launches the real jar, not a mock
mvn package -DskipTests
```

## Running the suite

```bash
robot --outputdir target/robot-results src/test/robot/functional_encryption_pipeline.robot
```

What happens:

- **Suite Setup** launches `target/quarkus-app/quarkus-run.jar` with
  `QUARKUS_PROFILE=test`, which activates every `%test.*` property (fast 2s
  poll cron, its own log level, port `8082` so it won't collide with a `dev`
  instance already running on `8081`, etc.) and waits for the service's own
  startup log line before proceeding.
- The test opens connections to both the input and output SFTP servers,
  uploads `testdata/sample_input.csv` to the input server as
  `robot_functional_test.csv`, and polls (up to 30s) until the encrypted
  output shows up on the output server.
- **Suite Teardown** always terminates the launched process, pass or fail.

Results: `target/robot-results/report.html` and `log.html`.

## Test cases

- **Happy path**: `File Dropped In SFTP Input Is Encrypted And Delivered To Output`
- **Edge cases** (tag `edge-case`): empty (0-byte) file, filename with spaces/special
  characters, and a binary (non-text) file - all proving the pipeline doesn't
  special-case size, filename shape, or content type.
- **Negative cases** (tag `negative`): a hidden dot-prefixed file is never picked up,
  a file dropped straight into `input/done/` is never treated as new work, and an
  already-processed file is never reprocessed on a later poll.

Run only one group with `--include`:

```bash
robot --outputdir target/robot-results --include edge-case src/test/robot/functional_encryption_pipeline.robot
robot --outputdir target/robot-results --include negative src/test/robot/functional_encryption_pipeline.robot
```

Note: the negative-case tests each include a `Sleep    ${NEGATIVE_CASE_OBSERVATION_WINDOW}`
(20s) to give several real `%test.` poll cycles (2s cron) a chance to run before
asserting absence - so the full suite now takes noticeably longer than before.

## Notes

- The suite is idempotent - it removes any leftover files from a previous run
  (input, output, and done copies) both before uploading and after a
  successful assertion, so re-running it doesn't need manual cleanup.
- `%test.` properties only need to list what genuinely differs from the base
  config in `application.properties` - anything not overridden there still
  falls back to the base values.
- This suite was syntax/keyword validated with `robot --dryrun` in
  development. A full live run (real jar + real SFTP server) needs Docker and
  Maven available locally / in CI - it wasn't executed against a live stack
  during this change.
