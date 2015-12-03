(function() {
  "use strict";

  var applicationDrawStyle = {fillColor: "#3CB8EA", fillOpacity: 0.35, strokeColor: "#0000FF", pointRadius: 6};
  var neighbourDrawStyle = {fillColor: "rgb(243,145,41)", // $lp-orange
                            fillOpacity: 0.35,
                            strokeColor: "rgb(239, 118, 38)", // $orange-darkest
                            pointRadius: 6};
  var applicationId;

  var borderCache = {};

  function Model() {
    var self = this;

    self.getApplicationWKT = null;
    self.applicationAreaLoading = ko.observable(false);

    self.getNeighbourWKT = null;
    self.neighborAreasLoading = ko.observable(false);

    self.areasLoading = ko.pureComputed(function() {
      return self.applicationAreaLoading() || self.neighborAreasLoading();
    });

    self.applicationId = ko.observable();
    self.neighbors = ko.observableArray();
    self.neighborId = ko.observable();
    self.map = null;


    var neighborSkeleton = {propertyId: undefined,
                            owner: {
                                address: {
                                  city: undefined,
                                  street: undefined,
                                  zip: undefined
                                },
                                businessID: undefined,
                                email: undefined,
                                name: undefined,
                                nameOfDeceased: undefined,
                                type: undefined
                            }};

    function ensureNeighbors(neighbor) { // ensure neighbors have correct properties defined
      var n = _.defaults(neighbor, neighborSkeleton);
      n.owner = _.defaults(n.owner, neighborSkeleton.owner); // _.defaults is not deep
      return n;
    }

    self.draw = function(propertyIds, drawingStyle, processing) {
      var processedIds = [];
      var found = [];
      var missing = [];

      _.each(propertyIds, function(p) {
        if (!_.contains(processedIds, p)) {
          if (borderCache[p]) {
            _.each(borderCache[p], function(wkt) {found.push(wkt);});
          } else {
            missing.push(p);
          }
          processedIds.push(p);
        }
      });

      self.map.drawDrawings(found, {}, drawingStyle);

      if (!_.isEmpty(missing)) {
        ajax.datatables("property-borders", {propertyIds: missing})
          .success(function(resp) {
            var results = [];
            _.each(resp.wkts, function(w) {
              if (borderCache[w.kiinttunnus]) {
                if (!_.contains(borderCache[w.kiinttunnus])) {
                  borderCache[w.kiinttunnus].push(w.wkt);
                }
              } else {
                borderCache[w.kiinttunnus] = [w.wkt];
              }

              results.push(w.wkt);
            });
            self.map.drawDrawings(results, {}, drawingStyle);
          })
          .processing(processing)
          .call();
      }
    };

    self.init = function(application) {
      var location = application.location,
          x = location.x,
          y = location.y;
      var neighbors = _.map(application.neighbors, ensureNeighbors);

      if (!self.map) {
        self.map = gis.makeMap("neighbors-map", false).addClickHandler(self.click);
        self.map.updateSize().center(x, y, 13);
      } else {
        self.map.updateSize().center(x, y).clear();
      }

      self.applicationId(application.id).neighbors(neighbors).neighborId(null);

      self.getApplicationWKT = self.draw([application.propertyId], applicationDrawStyle, self.applicationAreaLoading);
      self.getNeighbourWKT = self.draw(_.pluck(neighbors, "propertyId"), neighbourDrawStyle, self.neighborAreasLoading);
    };

    function openEditDialog(params) {
      hub.send("show-dialog", {ltitle: "neighbors.edit.title",
                               component: "neighbors-edit-dialog",
                               componentParams: params,
                               size: "medium"});
    }

    self.edit = function(neighbor) {
      openEditDialog({neighbor: neighbor});
    };

    self.add = function() {
      openEditDialog();
    };

    self.click = function(x, y) {
      hub.send("show-dialog", { ltitle: "neighbor.owners.title",
                                size: "large",
                                component: "neighbors-owners-dialog",
                                componentParams: {x: x,
                                                  y: y} });
    };

    self.done = function() {
      pageutil.openApplicationPage({id: applicationId}, "statement");
    };

    self.remove = function(neighbor) {
      self.neighborId(neighbor.id);
      LUPAPISTE.ModalDialog.showDynamicYesNo(
        loc("neighbors.remove-dialog.title"),
        loc("neighbors.remove-dialog.message"),
        {title: loc("yes"), fn: self.removeNeighbor},
        {title: loc("no")}
      );
      return self;
    };

    self.removeNeighbor = function() {
      ajax
        .command("neighbor-remove", {id: self.applicationId(), neighborId: self.neighborId()})
        .complete(_.partial(repository.load, self.applicationId(), _.noop))
        .call();
      return self;
    };
  }

  var model = new Model();

  hub.onPageLoad("neighbors", function(e) {
    applicationId = e.pagePath[0];
    repository.load(applicationId);
  });

  hub.onPageUnload("neighbors", function() {
    if (model.getApplicationWKT) {
      model.getApplicationWKT.abort();
      model.getApplicationWKT = null;
    }
    if (model.getNeighbourWKT) {
      model.getNeighbourWKT.abort();
      model.getNeighbourWKT = null;
    }
    if (model.map) {
      model.map.clear();
    }

    // Could reset borderCache here to save memory?
  });

  repository.loaded(["neighbors"], function(application) {
    if (applicationId === application.id) {
      model.init(application);
    }
  });

  $(function() {
    $("#neighbors-content").applyBindings(model);
  });
})();
