*** Settings ***
Documentation     Functional (black-box) test for ember-encryption-service.
...
...               Starts the real packaged service with quarkus.profile=test active
...               (so every %test.* property in application.properties is what's
...               actually in effect), drops a plain file into the SFTP input
...               directory exactly like a real upstream system would, and asserts
...               on the same two externally-observable outcomes a human would
...               check by hand: the encrypted `<name>.zip.pgp` lands in the output
...               directory, and the original is relocated into input/done/.
...
...               Prerequisites (see src/test/robot/README.md for full setup):
...                 1) Both local test SFTP servers (input + output) are running:
...                    docker compose up -d
...                 2) The service is packaged: mvn package -DskipTests
...
Resource          resources/common.resource
Suite Setup       Start Encryption Service With Test Profile
Suite Teardown    Stop Encryption Service
Test Setup        Open Test Sftp Connection
Test Teardown     Close Test Sftp Connection
Force Tags        functional    sftp    test-profile


*** Variables ***
${LOCAL_TEST_FILE}    ${CURDIR}${/}testdata${/}sample_input.csv


*** Test Cases ***
File Dropped In SFTP Input Is Encrypted And Delivered To Output
    [Documentation]    End-to-end happy path: a file placed in the SFTP input
    ...    directory should, within one or two %test. poll cycles, be zipped,
    ...    PGP-encrypted, and uploaded to the output directory as
    ...    <name>.zip.pgp - with the original source moved into input/done/.
    ${test_base_name} =    Generate Unique Test Base Name

    Drop File In Sftp Input    ${LOCAL_TEST_FILE}    ${test_base_name}

    Wait Until Keyword Succeeds    ${PROCESSING_TIMEOUT}    ${PROCESSING_POLL}
    ...    Output File Should Exist And Be Non Empty    ${test_base_name}

    Source File Should Be Moved To Done    ${test_base_name}


Empty File Is Still Zipped, Encrypted And Delivered
    [Documentation]    EDGE CASE: a genuinely 0-byte input file. Nothing in the
    ...    pipeline special-cases file size (ZipCompressionService.zip and
    ...    PgpEncryptionService.encrypt both just stream whatever bytes exist) -
    ...    the zip container's own header/footer plus the PGP framing mean the
    ...    final .zip.pgp is never truly empty, even when the source content is.
    ...    This locks that behavior in so a future change can't silently start
    ...    skipping or erroring on empty files.
    [Tags]    edge-case
    ${empty_file} =    Create Local Empty Test File
    ${test_base_name} =    Generate Unique Test Base Name

    Drop File In Sftp Input    ${empty_file}    ${test_base_name}

    Wait Until Keyword Succeeds    ${PROCESSING_TIMEOUT}    ${PROCESSING_POLL}
    ...    Output File Should Exist And Be Non Empty    ${test_base_name}

    Source File Should Be Moved To Done    ${test_base_name}


Filename With Spaces And Special Characters Is Handled Correctly
    [Documentation]    EDGE CASE: real upstream filenames aren't always clean
    ...    ASCII identifiers. Confirms a name with spaces, parentheses, an
    ...    ampersand and a hash survives the full round trip (download, zip
    ...    entry name, PGP literal-data filename hint, upload, done-move)
    ...    without the pipeline mangling or rejecting it.
    ...
    ...    Deliberately AVOIDS square brackets, "*" and "?" - those aren't just
    ...    "special characters" to the app, they're fnmatch glob metacharacters
    ...    to SSHLibrary's own `pattern=` matching (List Files In Directory /
    ...    Output File Should Exist And Be Non Empty / Source File Should Be
    ...    Moved To Done all match on it). A literal "[v1]" in a filename gets
    ...    parsed by fnmatch as a character class ("one char: v or 1"), not as
    ...    literal text, so the pattern would silently stop matching itself -
    ...    a test-tooling bug, not a pipeline bug. Keep this filename limited to
    ...    fnmatch-safe punctuation.
    [Tags]    edge-case
    ${timestamp} =    Evaluate    str(int(time.time()))    modules=time
    ${weird_name} =    Set Variable    robot test (${timestamp}) v1 & final #1.csv

    Drop File In Sftp Input    ${LOCAL_TEST_FILE}    ${weird_name}

    Wait Until Keyword Succeeds    ${PROCESSING_TIMEOUT}    ${PROCESSING_POLL}
    ...    Output File Should Exist And Be Non Empty    ${weird_name}

    Source File Should Be Moved To Done    ${weird_name}


Binary Non Text File Is Processed Like Any Other Byte Stream
    [Documentation]    EDGE CASE: the pipeline is documented as content-agnostic -
    ...    plain zip + PGP over raw bytes - and should never assume text/CSV.
    ...    Uploads a file containing the full 0-255 byte range, including NUL
    ...    bytes, and confirms it's still zipped, encrypted and delivered exactly
    ...    like the happy-path CSV.
    [Tags]    edge-case
    ${binary_file} =    Create Local Binary Test File
    ${test_base_name} =    Generate Unique Test Base Name With Extension    bin

    Drop File In Sftp Input    ${binary_file}    ${test_base_name}

    Wait Until Keyword Succeeds    ${PROCESSING_TIMEOUT}    ${PROCESSING_POLL}
    ...    Output File Should Exist And Be Non Empty    ${test_base_name}

    Source File Should Be Moved To Done    ${test_base_name}


Hidden Dot Prefixed File Is Never Picked Up
    [Documentation]    NEGATIVE CASE: listInputObjects() explicitly excludes any
    ...    name starting with "." (see SftpObjectService.java:133). A dot-file
    ...    dropped into the input root should sit there completely untouched -
    ...    never encrypted, never uploaded, never moved to done/ - across
    ...    multiple real poll cycles.
    [Tags]    negative
    ${timestamp} =    Evaluate    str(int(time.time()))    modules=time
    ${hidden_name} =    Set Variable    .hidden_robot_test_${timestamp}.csv

    Drop File In Sftp Input    ${LOCAL_TEST_FILE}    ${hidden_name}

    # No Wait Until Keyword Succeeds here on purpose - we're proving something
    # does NOT happen, so give it several real poll cycles and check once.
    Sleep    ${NEGATIVE_CASE_OBSERVATION_WINDOW}

    Output File Should Not Exist    ${hidden_name}
    Source File Should Still Be In Input Root    ${hidden_name}


File Dropped Directly Into Done Directory Is Ignored
    [Documentation]    NEGATIVE CASE: listInputObjects() is non-recursive by
    ...    design (see FileStorageService's javadoc) - done/ is a sub-location,
    ...    not part of the pending backlog. A file placed straight into done/
    ...    (bypassing the normal moveToDone flow entirely) must never be treated
    ...    as new work, however many poll cycles run.
    [Tags]    negative
    ${test_base_name} =    Generate Unique Test Base Name

    Put File In Sftp Input Done Directory    ${LOCAL_TEST_FILE}    ${test_base_name}

    Sleep    ${NEGATIVE_CASE_OBSERVATION_WINDOW}

    Output File Should Not Exist    ${test_base_name}


Already Processed File Is Not Reprocessed On The Next Poll
    [Documentation]    NEGATIVE CASE / idempotency: once a file is moved to
    ...    done/, it must never be picked up, re-encrypted or re-uploaded again
    ...    on a later poll cycle - proving the "no per-file existsInProcessed
    ...    check" optimization (see FileProcessingService's javadoc) doesn't
    ...    reintroduce duplicate processing at the same time it speeds things up.
    [Tags]    negative
    ${test_base_name} =    Generate Unique Test Base Name
    Drop File In Sftp Input    ${LOCAL_TEST_FILE}    ${test_base_name}

    Wait Until Keyword Succeeds    ${PROCESSING_TIMEOUT}    ${PROCESSING_POLL}
    ...    Output File Should Exist And Be Non Empty    ${test_base_name}
    Source File Should Be Moved To Done    ${test_base_name}

    ${count_before} =    Count Matching Files In Sftp Directory
    ...    ${OUTPUT_SFTP_ALIAS}    ${SFTP_OUTPUT_DIR}    ${test_base_name}.zip.pgp

    # Let at least one more full poll cycle pass.
    Sleep    ${NEGATIVE_CASE_OBSERVATION_WINDOW}

    ${count_after} =    Count Matching Files In Sftp Directory
    ...    ${OUTPUT_SFTP_ALIAS}    ${SFTP_OUTPUT_DIR}    ${test_base_name}.zip.pgp
    Should Be Equal As Integers    ${count_before}    ${count_after}
    ...    msg=${test_base_name}.zip.pgp count changed after an extra poll cycle - file was reprocessed.
