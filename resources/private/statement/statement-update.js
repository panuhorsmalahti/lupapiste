LUPAPISTE.StatementUpdate = function(params) {
  "use strict";
  var self = this;

  var applicationId = params.applicationId;
  var statementId = params.statementId;
  var application = params.application;
  var data = params.data;
  var dirty = params.dirty;
  var goingToSubmit = params.goingToSubmit;

  var saveDraftCommand = params.saveDraftCommandName;
  var submitCommand = params.submitCommandName;

  var init = params.init;
  var getCommandParams = params.getCommandParams;

  var saving = ko.observable(false);
  var modifyId = ko.observable(util.randomElementId());

  var draftTimerId = undefined;

  var doSubmit = ko.pureComputed(function() {
    return !saving() && goingToSubmit();
  });

  application.subscribe(function(application) {
    var statement = _.find(util.getIn(application, ["statements"]), function(statement) {
      return statement.id === statementId();
    });
    if(statement) {
      if (!statement["modify-id"]) {
        statement["modify-id"] = "";
      }
      data(ko.mapping.fromJS(statement));
      init(statement);

    } else {
      pageutil.openPage("404");
    }
  });

  function updateModifyId() {
    data()["modify-id"](modifyId());
    modifyId(util.randomElementId());
  }

  doSubmit.subscribe(function(doSubmit) {
    var params = getCommandParams();
    if (!saving() && goingToSubmit()) {
      saving(true);
      clearTimeout(draftTimerId);
      ajax
        .command(submitCommand, _.extend({
          id: applicationId(),
          "modify-id": modifyId(),
          "prev-modify-id": util.getIn(data(), ["modify-id"], ""),
          statementId: statementId(),
          lang: loc.getCurrentLanguage()
        }, params))
        .success(function() {
          updateModifyId();
          pageutil.openApplicationPage({id: applicationId()}, "statement");
          repository.load(applicationId());
          hub.send("indicator-icon", {clear: true});
          hub.send("indicator", {style: "positive"});
          return false;
        })
        .error(function() {
          hub.send("indicator", {style: "negative"});
        })
        .fail(function() {
          hub.send("indicator", {style: "negative"});
        })
        .complete(function() {
          goingToSubmit(false);
          saving(false);
        })
        .call();
    }
    return false;
  });

  function updateDraft(id) {
    var params = getCommandParams();
    if (statementId() === id) {
      saving(true);
      dirty(false);
      ajax
        .command(saveDraftCommand, _.extend({
          id: applicationId(),
          "modify-id": modifyId(),
          "prev-modify-id": util.getIn(data(), ["modify-id"], ""),
          statementId: statementId(),
          lang: loc.getCurrentLanguage()
        }, params))
        .success(function() {
          updateModifyId();
          hub.send("indicator-icon", {style: "positive"});
          return false;
        })
        .complete(function() {
          saving(false);
        })
        .call();
    }
    return false;
  }

  dirty.subscribe(function(dirty) {
    clearTimeout(draftTimerId);
    if (dirty) {
      draftTimerId = _.delay(_.partial(updateDraft, statementId()), 2000);
    }
  });
};
