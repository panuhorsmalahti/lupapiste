*** Settings ***

Documentation   Mikko creates a new application
Resource        ../../common_resource.robot
Suite teardown  Logout

*** Test Cases ***

Mikko opens an application
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  create-app${secs}
  Set Suite Variable  ${propertyId}  753-423-2-41
  Create application the fast way  ${appname}  ${propertyId}  kerrostalo-rivitalo
  Submit application
  Logout

Readonly authority opens the application
  Authority logs in  luukas  luukas  Luukas Lukija
  Open application  ${appname}  ${propertyId}

Form is disabled
  Wait Until  Element should be disabled  xpath=//section[@data-doc-type="hankkeen-kuvaus"]//textarea
  Element should not be visible  xpath=//button[@data-test-id='reject-doc-hankkeen-kuvaus']

Actions are disabled
  Open tab  requiredFieldSummary
  Element should not be visible  xpath=//button[@data-test-id="approve-application-summaryTab"]
  Element should not be visible  xpath=//button[@data-test-id="approve-application"]

Commenting is enabled
  Add comment  All is well

Notices are enabled
  Open side panel  notice
  Input text  xpath=//div[@id='notice-panel']//textarea[@data-test-id='application-authority-notice']  Asiakas on m...miellyttävä

Urgency is not enables
  Element Should Not Be Visible  xpath=//select[@data-test-id="notice-urgency-select"]
