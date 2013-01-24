*** Settings ***

Documentation   Application gets verdict
Suite teardown  Logout
Resource        ../../common_resource.robot

*** Test Cases ***

Mikko opens application to see verdict
  Mikko logs in
  Wait and click by test class  application-link
  Sleep  1

Application does not have verdict
  Click element  test-verdict-tab
  Element should not be visible  test-application-verdict
  ${ID} =  Get Element Attribute  xpath=//*[@data-bind-test-id='application-id']@data-test-value
  Set suite variable  ${APPLICATION ID}  ${ID}
  Logout

Solita Admin can log in and gives verdict
  SolitaAdmin logs in
  Wait until page contains element  admin-header
  Log  ${APPLICATION ID}
  Wait until  page should contain link  ${APPLICATION ID}
  Click link  ${APPLICATION ID}
  Logout

Mikko opens application
  Mikko logs in
  Wait and click by test class  application-link
  
Application verdict is visible to applicant
  Wait and click  test-verdict-tab
  Wait Until  Element should be visible  test-application-verdict
  Logout

Sonja opens application
  Sonja logs in
  Wait and click by test class  application-link

Application verdict is visible to authority
  Click element  test-verdict-tab
  Wait Until  Element should be visible  test-application-verdict
