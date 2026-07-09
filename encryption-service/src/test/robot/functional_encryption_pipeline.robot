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
...                 1) The local test SFTP server is running: docker compose up -d
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

    SSHLibrary.Put File    ${LOCAL_TEST_FILE}    ${SFTP_INPUT_DIR}/${test_base_name}

    Wait Until Keyword Succeeds    ${PROCESSING_TIMEOUT}    ${PROCESSING_POLL}
    ...    Output File Should Exist And Be Non Empty    ${test_base_name}

    Source File Should Be Moved To Done    ${test_base_name}
