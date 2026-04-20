*** Settings ***
Library    Process
Library    OperatingSystem

*** Variables ***
${DEVICE_SERVER}    1B221FDEE0033R
${DEVICE_CLIENT}    6dcede68
${APP_PACKAGE}    com.example.lanchat

*** Test Cases ***
Dual Device Discovery Test
    [Documentation]    Test UDP discovery between two Android devices

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

    # Final verification
    Should Be True    ${discovery_success} or ${ui_check}    Discovery failed - neither logs nor UI show peer

    Log    ===== DISCOVERY SUCCESSFUL =====

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
