LUPAPISTE.AutocompleteTagsModel = function(params) {
  "use strict";
  var self = this;

  self.lPlaceholder = params.lPlaceholder;

  self.selected = lupapisteApp.services.tagFilterService.selected;

  self.query = ko.observable("");

  self.data = ko.pureComputed(function() {
    var result = [];

    for (var key in lupapisteApp.services.tagFilterService.data()) {
      var header = {label: lupapisteApp.services.tagFilterService.data()[key].name[loc.currentLanguage], groupHeader: true};

      var filteredData = util.filterDataByQuery({data: lupapisteApp.services.tagFilterService.data()[key].tags,
                                                 query: self.query(),
                                                 selected: self.selected()});
      // append group header and group items to result data
      if (filteredData.length > 0) {
        if (_.keys(lupapisteApp.services.tagFilterService.data()).length > 1) {
          result = result.concat(header);
        }
        result = result.concat(_.sortBy(filteredData, "label"));
      }
    }
    return result;
  });
};
