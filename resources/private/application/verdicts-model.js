LUPAPISTE.VerdictsModel = function() {
  "use strict";
  var self = this;

  function getApplicationId(bindings) {
    return bindings.application.id();
  }

  self.verdicts = ko.observable();
  self.processing = ko.observable(false);
  self.pending = ko.observable(false);

  self.refresh = function(application) {
    var manuallyUploadedAttachments = _.filter(application.attachments, function(attachment) {
      return _.isEqual(attachment.target, {type: "verdict"});});

    var verdicts = _.cloneDeep(application.verdicts || []).map(function(verdict) {
      var paatokset = _.map(verdict.paatokset || [], function(paatos) {
        var poytakirjat = _.map(paatos.poytakirjat || [], function(pk) {
          var myId = {type: "verdict", id: pk.urlHash};
          var myAttachments = _.filter(application.attachments || [], function(attachment) {return _.isEqual(attachment.target, myId);}) || [];
          pk.attachments = myAttachments;
          if (manuallyUploadedAttachments) {
            pk.attachments = pk.attachments.concat(manuallyUploadedAttachments);
            manuallyUploadedAttachments = null;
          }
          return pk;
        });
        paatos.poytakirjat = poytakirjat;
        return paatos;});
      verdict.paatokset = paatokset;
      return verdict;
    });

    self.verdicts(verdicts);
  };

  self.openVerdict = function(bindings) {
    window.location.hash = "#!/verdict/" + getApplicationId(bindings);
    return false;
  };

  self.checkVerdict = function(bindings){
    var applicationId = getApplicationId(bindings);
    ajax.command("check-for-verdict", {id: applicationId})
    .processing(self.processing)
    .pending(self.pending)
    .success(function(d) {
      var content = loc("verdict.verdicts-found-from-backend", d.verdictCount);
      LUPAPISTE.ModalDialog.showDynamicOk(loc("verdict.fetch.title"), content);
      pageutil.showAjaxWait();
      repository.load(applicationId);
    })
    .call();
  };
};
