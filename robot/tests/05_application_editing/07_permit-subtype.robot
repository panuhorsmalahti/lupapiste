*** Settings ***

Documentation   Mikko edits application subtype
Resource        ../../common_resource.robot

*** Test Cases ***

Mikko creates an application
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  poik-${secs}
  Create application the fast way  ${appname}  753-416-25-33  poikkeamis

Poikkari default subtype = poikkeamislupa
  List Selection Should Be  permitSubtypeSelect  poikkeamislupa

Change permit subtype
  Select From List By Value  permitSubtypeSelect  suunnittelutarveratkaisu
  Wait Until Element Is Visible  permitSubtypeSaveIndicator
  Go to page  applications
  Open application  ${appname}  753-416-25-33
  List Selection Should Be  permitSubtypeSelect  suunnittelutarveratkaisu
  [Teardown]  Logout
