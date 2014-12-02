*** Settings ***

Documentation  Stamping functionality for authority
Suite setup     Apply minimal fixture now
Suite teardown  Logout
Resource       ../../common_resource.robot
Variables      variables.py

*** Test Cases ***

Set stamping info variables
  Set Suite Variable  ${STAMP_TEXT}  Hyvaksyn
  Set Suite Variable  ${STAMP_DATE}  12.12.2012
  Set Suite Variable  ${STAMP_ORGANIZATION}  LupaRobot
  Set Suite Variable  ${STAMP_XMARGIN}  12
  Set Suite Variable  ${STAMP_YMARGIN}  88
  Set Suite Variable  ${STAMP_TRANSPARENCY_IDX}  2

Mikko creates application and goes to empty attachments tab
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  stamping${secs}
  Mikko logs in
  Create application the fast way  ${appname}  753  753-416-25-30  asuinrakennus
  Open tab  attachments

Mikko adds PDF attachment without comment
  Add attachment  ${PDF_TESTFILE_PATH1}  ${EMPTY}  Uusi asuinrakennus
  Wait Until  Element should be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[contains(., '${PDF_TESTFILE_NAME1}')]
  Add attachment  ${PDF_TESTFILE_PATH2}  ${EMPTY}  Uusi asuinrakennus
  Wait Until  Element should be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[contains(., '${PDF_TESTFILE_NAME2}')]
  Add attachment  ${PDF_TESTFILE_PATH3}  ${EMPTY}  Yleisesti hankkeeseen
  Wait Until  Element should be visible  xpath=//div[@data-test-id='application-pre-attachments-table']//a[contains(., '${PDF_TESTFILE_NAME3}')]
  
Mikko does not see stamping button
  Element should not be visible  xpath=//div[@id="application-attachments-tab"]//button[@data-test-id="stamp-attachments-btn"]
  
Mikko submits application for authority
  Submit application
  
Sonja logs in
  Logout
  Sonja logs in
  
Sonja goes to attachments tab
  Open application  ${appname}  753-416-25-30
  Open tab  attachments

Sonja sees stamping button
  Element should be visible  xpath=//div[@id="application-attachments-tab"]//button[@data-test-id="stamp-attachments-btn"]
  
Sonja clicks stamp button, stamping page opens
  Click element  xpath=//div[@id="application-attachments-tab"]//button[@data-test-id="stamp-attachments-btn"]
  Wait Until  Element should be visible  stamping-container

Sonja sees stamping info fields
  Element should be visible  stamp-info
  Element should be visible  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-text"]
  Element should be visible  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-date"]
  Element should be visible  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-organization"]
  Element should be visible  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-xmargin"]
  Element should be visible  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-ymargin"]
  Element should be visible  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//select[@data-test-id="stamp-info-transparency"]

Sonja inputs new stamping info values
  Input text by test id  stamp-info-text  ${STAMP_TEXT}
  Input text by test id  stamp-info-date  ${STAMP_DATE}
  Input text by test id  stamp-info-organization  ${STAMP_ORGANIZATION}
  Input text by test id  stamp-info-xmargin  ${STAMP_XMARGIN}
  Input text by test id  stamp-info-ymargin  ${STAMP_YMARGIN}

Sonja can go to attachments tab. When she returns, stamp info fields are persistent.
  Click element  xpath=//div[@id="stamping-container"]//a[@data-test-id="back-to-application-from-stamping"]
  Element should be visible  application-attachments-tab
  Click element  xpath=//div[@id="application-attachments-tab"]//button[@data-test-id="stamp-attachments-btn"]
  Element should be visible  stamp-info  
  Textfield value should be  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-text"]  ${STAMP_TEXT}
  Textfield value should be  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-date"]  ${STAMP_DATE}
  Textfield value should be  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-organization"]  ${STAMP_ORGANIZATION}
  Textfield value should be  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-xmargin"]  ${STAMP_XMARGIN}
  Textfield value should be  xpath=//div[@id="stamping-container"]//form[@id="stamp-info"]//input[@data-test-id="stamp-info-ymargin"]  ${STAMP_YMARGIN}
  
Sonja can toggle selection of attachments by group/all/none
  # Mikko uploaded 2 attachments belonging to operation "Uusi asuinrakennus" and 1 attachment to "Yleiset hankkeen liitteet"
  Click element  xpath=//div[@id="stamping-container"]//tr[@data-test-id="asuinrakennus"]//a[@data-test-id="attachments-group-select"]
  Xpath should match x times  //div[@id="stamping-container"]//tr[contains(@class,'selected')]  2
  Click element  xpath=//div[@id="stamping-container"]//tr[@data-test-id="asuinrakennus"]//a[@data-test-id="attachments-group-deselect"]
  Xpath should match x times  //div[@id="stamping-container"]//tr[contains(@class,'selected')]  0
  Click element  xpath=//div[@id="stamping-container"]//tr[@data-test-id="attachments.general"]//a[@data-test-id="attachments-group-select"]
  Xpath should match x times  //div[@id="stamping-container"]//tr[contains(@class,'selected')]  1
  Click element  xpath=//div[@id="stamping-container"]//a[@data-test-id="stamp-select-all"]
  Xpath should match x times  //div[@id="stamping-container"]//tr[contains(@class,'selected')]  3
  Click element  xpath=//div[@id="stamping-container"]//a[@data-test-id="stamp-select-none"]
  Xpath should match x times  //div[@id="stamping-container"]//tr[contains(@class,'selected')]  0
  
Status of stamping is ready
  Element text should be  xpath=//div[@id="stamping-container"]//span[@data-test-id="stamp-status-text"]  Valmiina leimaamaan liitteet
  
Select all files and start stamping
  Click element  xpath=//div[@id="stamping-container"]//a[@data-test-id="stamp-select-all"]
  Click element  xpath=//div[@id="stamping-container"]//button[@data-test-id="start-stamping"]
  Xpath should match x times  //div[@id="stamping-container"]//span[@data-test-id="attachment-status-text"]  3
  Wait Until  Element text should be  xpath=//div[@id="stamping-container"]//span[@data-test-id="stamp-status-text"]  Leimaus valmis

Return from stamping to attachments tab
  Click element  xpath=//div[@id="stamping-container"]//button[@data-test-id="stamp-ok"]
  Element should be visible  application-attachments-tab