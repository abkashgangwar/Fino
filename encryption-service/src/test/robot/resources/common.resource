*** Settings ***
Documentation     Shared variables and keywords for the ember-encryption-service
...               functional test suite. Talks to the service purely from the
...               outside - over SFTP and via the packaged jar's process/log -
...               exactly like a real client would, with no white-box access
...               to the app's internals.
Library           SSHLibrary
Library           Process
Library           OperatingSystem
Library           Collections


*** Variables ***
# ---------- SFTP input server (local docker-compose "sftp-input" container - see docker-compose.yml) ----------
${SFTP_INPUT_HOST}        localhost
${SFTP_INPUT_PORT}        2222
${SFTP_INPUT_USERNAME}    testuser
${SFTP_INPUT_PASSWORD}    testpass
${SFTP_INPUT_DIR}         /upload/input
${SFTP_DONE_DIR}          /upload/input/done

# ---------- SFTP output server - a SEPARATE container ("sftp-output") from the input
# one above, its own port/credentials - see docker-compose.yml ----------
${SFTP_OUTPUT_HOST}       localhost
${SFTP_OUTPUT_PORT}       2223
${SFTP_OUTPUT_USERNAME}   testuser2
${SFTP_OUTPUT_PASSWORD}   testpass2
${SFTP_OUTPUT_DIR}        /upload/output

# Connection aliases used with SSHLibrary.Switch Connection below, since the suite now
# has to juggle two live SFTP connections instead of one.
${INPUT_SFTP_ALIAS}       input-sftp
${OUTPUT_SFTP_ALIAS}      output-sftp

# ---------- Service under test ----------
# Repo root, resolved relative to this resource file (src/test/robot/resources/../../../..)
${PROJECT_ROOT}           ${CURDIR}${/}..${/}..${/}..${/}..
${SERVICE_JAR}            ${PROJECT_ROOT}${/}target${/}quarkus-app${/}quarkus-run.jar
${SERVICE_LOG_DIR}        ${PROJECT_ROOT}${/}target${/}robot-functional-logs
${SERVICE_STDOUT_LOG}     ${SERVICE_LOG_DIR}${/}quarkus-test-profile.stdout.log
${SERVICE_STDERR_LOG}     ${SERVICE_LOG_DIR}${/}quarkus-test-profile.stderr.log
${SERVICE_ALIAS}          ember-encryption-service-test
# Logged by FileProcessingService.onStart() once the app has fully initialized.
${SERVICE_READY_MARKER}   Encryption service started
${SERVICE_STARTUP_TIMEOUT}    60s
${SERVICE_STARTUP_POLL}       2s

# %test.app.poll.cron in application.properties fires every 2s under the test
# profile, so 30s gives a comfortable margin over several poll cycles.
${PROCESSING_TIMEOUT}     30s
${PROCESSING_POLL}        2s


*** Keywords ***
Start Encryption Service With Test Profile
    [Documentation]    Launches the packaged jar with quarkus.profile=test so every
    ...    %test.* property in application.properties takes effect, then blocks
    ...    until the service's own startup log line confirms it is up and polling.
    Create Directory    ${SERVICE_LOG_DIR}
    ${env} =    Create Dictionary    QUARKUS_PROFILE=test
    Start Process    java    -jar    ${SERVICE_JAR}
    ...    alias=${SERVICE_ALIAS}
    ...    env:QUARKUS_PROFILE=test
    ...    stdout=${SERVICE_STDOUT_LOG}
    ...    stderr=${SERVICE_STDERR_LOG}
    ...    cwd=${PROJECT_ROOT}
    Wait Until Keyword Succeeds    ${SERVICE_STARTUP_TIMEOUT}    ${SERVICE_STARTUP_POLL}
    ...    Service Log Should Contain Ready Marker

Service Log Should Contain Ready Marker
    [Documentation]    Fails (so Wait Until Keyword Succeeds retries) until the
    ...    ready marker line shows up in the captured stdout log.
    ${matches} =    Grep File    ${SERVICE_STDOUT_LOG}    ${SERVICE_READY_MARKER}
    Should Not Be Empty    ${matches}
    ...    msg=Service has not logged startup readiness yet.

Stop Encryption Service
    [Documentation]    Best-effort shutdown - always attempted from Suite Teardown
    ...    even if a test above failed, so no orphan JVM is left running.
    ${result} =    Run Keyword And Ignore Error    Terminate Process    ${SERVICE_ALIAS}
    Log    Stop Encryption Service result: ${result}

Open Test Sftp Connection
    [Documentation]    Opens ONE connection per server (input and output are now two
    ...    separate SFTP servers) under distinct aliases, so later keywords can
    ...    Switch Connection to whichever side they need to talk to.
    SSHLibrary.Open Connection    ${SFTP_INPUT_HOST}    port=${SFTP_INPUT_PORT}    alias=${INPUT_SFTP_ALIAS}
    SSHLibrary.Login    ${SFTP_INPUT_USERNAME}    ${SFTP_INPUT_PASSWORD}
    SSHLibrary.Open Connection    ${SFTP_OUTPUT_HOST}    port=${SFTP_OUTPUT_PORT}    alias=${OUTPUT_SFTP_ALIAS}
    SSHLibrary.Login    ${SFTP_OUTPUT_USERNAME}    ${SFTP_OUTPUT_PASSWORD}

Close Test Sftp Connection
    Run Keyword And Ignore Error    SSHLibrary.Close All Connections

Drop File In Sftp Input
    [Documentation]    Uploads a local file into the INPUT server's input directory.
    ...    Hides the connection-switching detail from the test suite itself.
    [Arguments]    ${local_path}    ${remote_name}
    SSHLibrary.Switch Connection    ${INPUT_SFTP_ALIAS}
    SSHLibrary.Put File    ${local_path}    ${SFTP_INPUT_DIR}/${remote_name}

Generate Unique Test Base Name
    [Documentation]    The local test SFTP server (atmoz/sftp) is locked down to
    ...    the SFTP subsystem only - it rejects plain SSH "exec" commands (rm, wc,
    ...    stat, ...) outright, so there is no remote "delete leftover file from
    ...    last run" option available to this suite. Using a fresh, timestamped
    ...    file name per run sidesteps that entirely: nothing needs to be cleaned
    ...    up, and a stale file from a previous run can never cause a false pass.
    ${timestamp} =    Evaluate    str(int(time.time()))    modules=time
    RETURN    robot_functional_test_${timestamp}.csv

Output File Should Exist And Be Non Empty
    [Documentation]    Fails (so Wait Until Keyword Succeeds retries) until the
    ...    encrypted output has been uploaded by a poll cycle. Downloads the file
    ...    to check its size, since the server has no "stat"/exec access and
    ...    SSHLibrary itself has no remote file-size keyword.
    [Arguments]    ${base_name}
    SSHLibrary.Switch Connection    ${OUTPUT_SFTP_ALIAS}
    ${files} =    SSHLibrary.List Files In Directory    ${SFTP_OUTPUT_DIR}    pattern=${base_name}.zip.pgp
    Should Not Be Empty    ${files}
    ...    msg=${base_name}.zip.pgp has not appeared in ${SFTP_OUTPUT_DIR} yet.

    ${local_copy} =    Set Variable    ${SERVICE_LOG_DIR}${/}${base_name}.zip.pgp
    SSHLibrary.Get File    ${SFTP_OUTPUT_DIR}/${base_name}.zip.pgp    ${local_copy}
    ${size} =    OperatingSystem.Get File Size    ${local_copy}
    OperatingSystem.Remove File    ${local_copy}
    Should Be True    ${size} > 0
    ...    msg=${base_name}.zip.pgp was uploaded but is empty (0 bytes).

Source File Should Be Moved To Done
    [Documentation]    Confirms FileStorageService.moveToDone relocated the
    ...    original source file - pure SFTP listing, no exec needed.
    [Arguments]    ${base_name}
    SSHLibrary.Switch Connection    ${INPUT_SFTP_ALIAS}
    ${done_files} =    SSHLibrary.List Files In Directory    ${SFTP_DONE_DIR}    pattern=${base_name}
    Should Not Be Empty    ${done_files}
    ...    msg=${base_name} was encrypted but the source was not moved to ${SFTP_DONE_DIR}.
