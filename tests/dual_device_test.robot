*** Settings ***
Library    Process
Library    OperatingSystem

*** Variables ***
${DEVICE_SERVER}    1B221FDEE0033R
${DEVICE_CLIENT}    6dcede68
${APP_PACKAGE}    com.example.lanchat

*** Test Cases ***
Dual Device Full Connection Test
    [Documentation]    Test UDP discovery AND TCP connection between two Android devices

    # Stop app on both devices
    Stop App On Device    ${DEVICE_SERVER}
    Stop App On Device    ${DEVICE_CLIENT}

    # Clear logs
    Clear Logcat On Device    ${DEVICE_SERVER}
    Clear Logcat On Device    ${DEVICE_CLIENT}

    # Launch app on both devices
    Launch App On Device    ${DEVICE_SERVER}
    Launch App On Device    ${DEVICE_CLIENT}

    # Wait for apps to start
    Sleep    3s

    # Verify apps started
    Log    Verifying apps started...
    ${server_log}=    Get Full Logcat On Device    ${DEVICE_SERVER}
    Should Contain    ${server_log}    MainActivity

    ${client_log}=    Get Full Logcat On Device    ${DEVICE_CLIENT}
    Should Contain    ${client_log}    MainActivity

    # Server device - tap Start Server button
    Log    Tapping Start Server on server device...
    Tap Button On Device    ${DEVICE_SERVER}    540    2900

    # Wait for server to start broadcasting
    Sleep    3s

    # Verify server is broadcasting
    Log    Checking server broadcasts...
    ${server_log}=    Get Full Logcat On Device    ${DEVICE_SERVER}
    Should Contain    ${server_log}    LANCHAT_DISCOVER

    # Client device - tap CLIENT tab first
    Log    Tapping CLIENT tab on client device...
    Tap Button On Device    ${DEVICE_CLIENT}    786    395

    # Wait for tab switch
    Sleep    1s

    # Client device - tap Start Discovery
    Log    Tapping Start Discovery on client device...
    Tap Button On Device    ${DEVICE_CLIENT}    540    2200

    # Wait for discovery (UDP broadcasts every 2s, need to catch at least 1)
    Sleep    5s

    # Check if Client received broadcasts
    Log    Checking if client discovered server...
    ${client_log}=    Get Full Logcat On Device    ${DEVICE_CLIENT}

    # Verify discovery happened
    ${discovery_success}=    Evaluate    "LANCHAT_DISCOVER" in """${client_log}"""
    Log    Client received UDP broadcasts: ${discovery_success}

    # Check if peer appears in UI
    ${ui_check}=    Check UI For Text On Device    ${DEVICE_CLIENT}    Pixel 6 Pro
    Log    Peer visible in UI: ${ui_check}

    # Final verification for discovery phase
    Should Be True    ${discovery_success} or ${ui_check}    Discovery failed - neither logs nor UI show peer

    Log    ===== DISCOVERY SUCCESSFUL =====

    # ===== PHASE 2: TCP CONNECTION =====
    Log    ===== Starting TCP Connection Phase =====

    # Clear client logcat to see only connection logs
    Clear Logcat On Device    ${DEVICE_CLIENT}

    # Client device - tap on the peer item to connect
    # Note: ListView item position varies, using center of peer list area
    Log    Tapping on peer to connect...
    Tap Button On Device    ${DEVICE_CLIENT}    540    1500

    # Wait for TCP connection to establish
    Sleep    5s

    # Check if TCP connection was established
    Log    Checking TCP connection logs on client...
    ${client_log}=    Get Full Logcat On Device    ${DEVICE_CLIENT}

    # Look for connection success indicators
    ${tcp_connected}=    Evaluate    "Connected to server" in """${client_log}""" or "ProtobufChannel" in """${client_log}"""
    Log    TCP connection established: ${tcp_connected}

    # Check server logs for client connection
    Log    Checking TCP connection logs on server...
    ${server_log}=    Get Full Logcat On Device    ${DEVICE_SERVER}
    ${server_got_connection}=    Evaluate    "Client connected to server" in """${server_log}""" or "ProtobufChannel" in """${server_log}"""
    Log    Server received connection: ${server_got_connection}

    # Verify at least one side shows successful connection
    Should Be True    ${tcp_connected} or ${server_got_connection}    TCP connection failed - no connection indicators found

    Log    ===== TCP CONNECTION SUCCESSFUL =====

    # ===== PHASE 3: MESSAGING (Optional - depends on UI) =====
    Log    ===== Testing Messaging Phase =====

    # Type a test message
    Log    Typing test message on client...
    Input Text On Device    ${DEVICE_CLIENT}    300    2700    Hello from Client!

    # Wait for message to be sent
    Sleep    2s

    # Check if message was sent
    ${client_log}=    Get Full Logcat On Device    ${DEVICE_CLIENT}
    ${message_sent}=    Evaluate    "Sent message" in """${client_log}""" or "Sent LanMessage" in """${client_log}"""
    Log    Message sent: ${message_sent}

    Log    ===== FULL INTEGRATION TEST COMPLETE =====

*** Keywords ***
Stop App On Device
    [Arguments]    ${device_id}
    Run Process    adb    -s    ${device_id}    shell    am    force-stop    ${APP_PACKAGE}

Launch App On Device
    [Arguments]    ${device_id}
    Run Process    adb    -s    ${device_id}    shell    am    start    -n    ${APP_PACKAGE}/.presentation.MainActivity

Clear Logcat On Device
    [Arguments]    ${device_id}
    Run Process    adb    -s    ${device_id}    shell    logcat    -c

Get Full Logcat On Device
    [Arguments]    ${device_id}
    ${result}=    Run Process    adb    -s    ${device_id}    shell    logcat    -d    shell=True
    RETURN    ${result.stdout}

Tap Button On Device
    [Arguments]    ${device_id}    ${x}    ${y}
    Run Process    adb    -s    ${device_id}    shell    input    tap    ${x}    ${y}

Check UI For Text On Device
    [Arguments]    ${device_id}    ${text}
    ${result}=    Run Process    adb    -s    ${device_id}    shell    uiautomator    dump    /sdcard/ui.xml    shell=True
    ${xml}=    Run Process    adb    -s    ${device_id}    shell    cat    /sdcard/ui.xml    shell=True
    ${found}=    Evaluate    """${text}""" in """${xml.stdout}"""
    RETURN    ${found}

Input Text On Device
    [Arguments]    ${device_id}    ${x}    ${y}    ${text}
    Run Process    adb    -s    ${device_id}    shell    input    tap    ${x}    ${y}
    Sleep    0.5s
    Run Process    adb    -s    ${device_id}    shell    input    text    ${text}
