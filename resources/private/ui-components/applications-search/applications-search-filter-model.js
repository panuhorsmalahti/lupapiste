LUPAPISTE.OrganizationTagsDataProvider = function(filtered) {
  "use strict";
  var self = this;

  self.query = ko.observable();

  self.filtered = filtered || ko.observableArray([]);

  var tagsData = ko.observable();

  ajax
    .query("get-organization-tags")
    .error(_.noop)
    .success(function(res) {
      tagsData(_.map(_.keys(res.tags), function(o) {
        return {organization: o, tags: res.tags[o]};
      }));
    })
    .call();

  self.data = ko.pureComputed(function() {
    var filteredData = _.filter(_.first(tagsData()).tags, function(tag) {
      return !_.some(self.filtered(), tag);
    });
    var q = self.query() || "";
    filteredData = _.filter(filteredData, function(tag) {
      return _.reduce(q.split(" "), function(result, word) {
        return _.contains(tag.label.toUpperCase(), word.toUpperCase()) && result;
      }, true);
    });
    return filteredData;
  });
};

LUPAPISTE.HandlersDataProvider = function() {
  "use strict";

  var self = this;

  self.query = ko.observable();

  var data = ko.observable();

  function mapUser(user) {
    var fullName = "";
    if (user) {
      if (user.lastName) { fullName += user.lastName; }
      if (user.firstName && user.lastName) { fullName += "\u00a0"; }
      if (user.firstName) { fullName +=  user.firstName; }
    }
    user.fullName = fullName;
    return user;
  }

  ajax
    .query("users-in-same-organizations")
    .error(_.noop)
    .success(function(res) {
      data(_.map(res.users, mapUser));
    })
    .call();

  self.data = ko.pureComputed(function() {
    var q = self.query() || "";
    return _.filter(data(), function(item) {
      return _.reduce(q.split(" "), function(result, word) {
        return _.contains(item.fullName.toUpperCase(), word.toUpperCase()) && result;
      }, true);
    });
  });
};

LUPAPISTE.ApplicationsSearchFilterModel = function(params) {
  "use strict";

  var self = this;

  self.dataProvider = params.dataProvider;

  self.organizationTagsDataProvider = null;
  self.handlersDataProvider = null;

  self.showAdvancedFilters = ko.observable(false);
  self.advancedFiltersText = ko.computed(function() {
    return self.showAdvancedFilters() ? "applications.filter.advancedFilter.hide" : "applications.filter.advancedFilter.show";
  });

  if ( lupapisteApp.models.currentUser.isAuthority() ) {
    self.handlersDataProvider = new LUPAPISTE.HandlersDataProvider();
    // TODO just search single organization tags for now, later do some grouping stuff in autocomplete component
    self.organizationTagsDataProvider = new LUPAPISTE.OrganizationTagsDataProvider(self.dataProvider.applicationTags);
  }
};
