LUPAPISTE.SidePanelModel = function() {
  "use strict";
  var self = this;

  self.typeId = undefined;

  self.applicationId = ko.observable();
  self.notice = ko.observable({});
  self.attachmentId = ko.observable();
  if (LUPAPISTE.NoticeModel) {
    self.notice(new LUPAPISTE.NoticeModel());
  }
  self.showConversationPanel = ko.observable(false);
  self.showNoticePanel = ko.observable(false);
  self.unseenComments = ko.observable();
  self.authorization = authorization.create();
  self.comment = ko.observable(comments.create());
  self.permitType = ko.observable();
  self.authorities = ko.observableArray([]);
  self.infoRequest = ko.observable();

  var AuthorityInfo = function(id, firstName, lastName) {
    this.id = id;
    this.firstName = firstName;
    this.lastName = lastName;
  };

  function initAuthoritiesSelectList(data) {
    var authorityInfos = [];
    _.each(data || [], function(authority) {
      authorityInfos.push(new AuthorityInfo(authority.id, authority.firstName, authority.lastName));
    });
    self.authorities(authorityInfos);
  }

  self.refresh = function(application, authorities) {
    self.applicationId(application.id);
    self.infoRequest(application.infoRequest);
    self.unseenComments(application.unseenComments);
    if (self.notice().refresh) {
      self.notice().refresh(application);
    }
    var type = pageutil.getPage();
    switch(type) {
      case "attachment":
      case "statement":
        self.comment().refresh(application, false, {type: type, id: pageutil.lastSubPage()});
        break;
      case "verdict":
        self.comment().refresh(application, false, {type: type, id: pageutil.lastSubPage()}, ["authority"]);
        break;
      default:
        self.comment().refresh(application, true);
        break;
    }
    self.permitType(application.permitType);
    initAuthoritiesSelectList(authorities);
  }

  var togglePanel = function(visible, button) {
    var panel = $("#side-panel, #side-panel-overlay");
    if(panel.hasClass("hide-side-panel")) {
      panel.toggleClass("hide-side-panel", 100);
    }
    else if(visible) {
      panel.toggleClass("hide-side-panel", 100);
    }
    $("#side-panel " + button).siblings().removeClass("active");
    $("#side-panel " + button).toggleClass("active");
  }

  self.toggleConversationPanel = function(data, event) {
    togglePanel(self.showConversationPanel(), ".btn-conversation");
    self.showConversationPanel(!self.showConversationPanel());
    self.showNoticePanel(false);

    setTimeout(function() {
      // Mark comments seen after a second
      if (self.applicationId() && self.authorization.ok("mark-seen")) {
        ajax.command("mark-seen", {id: self.applicationId(), type: "comments"})
          .success(function() {self.unseenComments(0);})
          .call();
      }}, 1000);
  };

  self.toggleNoticePanel = function(data, event) {
    togglePanel(self.showNoticePanel(), ".btn-notice");
    self.showConversationPanel(false);
    self.showNoticePanel(!self.showNoticePanel());
  };

  self.hideSidePanel = function(data, event) {
    if (self.showConversationPanel()) {
      self.toggleConversationPanel();
    }
    if (self.showNoticePanel()) {
      self.toggleNoticePanel();
    }
  }

  var pages = ["application","inforequest","attachment","statement","neighbors","verdict"];

  hub.subscribe({type: "page-change"}, function() {
    if(_.contains(pages, pageutil.getPage())) {
      $("#side-panel-template").addClass("visible");
    }
  });

  repository.loaded(pages, function(application, applicationDetails) {
    self.authorization.refreshWithCallback({id: applicationDetails.application.id}, function() {
      self.refresh(application, applicationDetails.authorities);
    });
  });
}

$(function() {
  var sidePanel = new LUPAPISTE.SidePanelModel();
  $("#side-panel-template").applyBindings(sidePanel);
});
