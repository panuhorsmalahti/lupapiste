LUPAPISTE.ForemanOtherApplicationsModel = function(params) {
  "use strict";
  var self = this;
  self.params = params;
  self.rows = ko.observableArray();
  self.autoupdatedRows = ko.observableArray();

  var createRow = function(model, index) {
    var res = _.filter(self.params.validationErrors, function(errors) {
      return _.isEqual(errors.path[0], self.params.path[0]);
    });

    var row = [];
    _.forEach(self.params.subSchema.body, function(subSchema) {
      var rowModel = model ? model[subSchema.name] : undefined;
      var readonly = false;
      if (_.contains(LUPAPISTE.config.foremanReadonlyFields, subSchema.name)) {
        readonly = util.getIn(model, ["autoupdated", "value"]) || false;
      }
      var item = {
        applicationId: self.params.applicationId,
        documentId: self.params.documentId,
        path: self.params.path,
        index: index,
        schema: subSchema,
        model: rowModel,
        validationErrors: res,
        readonly: readonly
      };
      row.push(item);
    });
    return row;
  };

  var rows = [];
  var autoupdatedRows = [];
  for (var key in self.params.model) {
    var model = self.params.model[key];
    if (util.getIn(model, ["autoupdated", "value"])) {
      autoupdatedRows.push(createRow(model, key));
    } else {
      rows.push(createRow(model, key));
    }
  }
  self.autoupdatedRows(autoupdatedRows);
  self.rows(rows);

  self.subscriptionIds = [];
  self.subscriptionIds.push(hub.subscribe("hetuChangedInBackend", function() {
    ajax.command("update-foreman-other-applications", {id: self.params.applicationId, foremanHetu: ""})
    .success(function() {
      repository.load(self.params.applicationId);
    })
    .call();
  }));

  self.subscriptionIds.push(hub.subscribe("hetuChanged", function(data) {
    ajax.command("update-foreman-other-applications", {id: self.params.applicationId, foremanHetu: data.value})
    .success(function() {
      repository.load(self.params.applicationId);
    })
    .call();
  }));

  self.addRow = function() {
    var lastItem = _.first(_.last(self.rows()));
    // If there are no rows added by user try calculate index from auto inserted rows
    if (_.isEmpty(self.rows())) {
      lastItem = _.first(_.last(self.autoupdatedRows()));
    }
    var index = lastItem && lastItem.index ? parseInt(lastItem.index) + 1 : 0;
    self.rows.push(createRow(undefined, index.toString()));
  };

  self.removeRow = function(row) {
    var index = _.first(row).index;
    var path = self.params.path.concat(index);

    LUPAPISTE.ModalDialog.showDynamicYesNo(loc("document.delete.header"), loc("document.delete.message"),
        { title: loc("yes"), fn: function () {
          ajax
            .command("remove-document-data", {
              doc: self.params.documentId,
              id: self.params.applicationId,
              path: path,
              collection: "documents"
            })
            .success(function() {
              self.rows.remove(row);
            })
            .call();
        } },
        { title: loc("no") });
  };
};

LUPAPISTE.ForemanOtherApplicationsModel.prototype.dispose = function() {
  "use strict";
  _.forEach(this.subscriptionIds, function(id) {
    hub.unsubscribe(id);
  });
};

ko.components.register("foreman-other-applications", {
  viewModel: LUPAPISTE.ForemanOtherApplicationsModel,
  template: { element: "foreman-other-applications" }
});