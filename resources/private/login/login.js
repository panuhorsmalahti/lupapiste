(function($) {
  "use strict";

  var self = this;

  var rememberMeCookieName = "my-email";

  var rememberMe = ko.observable(false);
  var processing = ko.observable(false);
  var pending = ko.observable(false);

  function recallMe() {
    var oldUsername = _.trim($.cookie(rememberMeCookieName));
    if (oldUsername) {
      rememberMe(true);
      $("#login-username").val(oldUsername.toLowerCase());
      $("#login-password").focus();
    } else {
      rememberMe(false);
      $("#login-username").focus();
    }
  }

  function login() {
    var username = _.trim($("#login-username").val());
    var password = $("#login-password").val();
    $("#login-message").text("").css("display", "none");

    if (rememberMe()) {
      $.cookie(rememberMeCookieName, username.toLowerCase(), { expires: 365, path: "/", secure: LUPAPISTE.config.cookie.secure});
    } else {
      $.removeCookie(rememberMeCookieName, {path: "/"});
    }

    ajax.postJson("/api/login", {"username": username, "password": password})
      .raw(false)
      .processing(processing)
      .pending(pending)
      .success(function(e) {
        var baseUrl = "/app/" + loc.getCurrentLanguage() + "/" + e.applicationpage;
        // get the server-stored hashbang or redirect URL to redirect to right page (see web.clj for details)
        ajax.query("redirect-after-login")
          .success(function(e) {
            var redirectLocation = baseUrl;
            if (e.url) {
              redirectLocation = _.startsWith(e.url, "/") ? e.url : baseUrl + "#!/" + e.url;
            }
            window.parent.location = redirectLocation;
          })
          .call();
      })
      .error(function(e) { hub.send("login-failure", e); })
      .call();
  }

  hub.subscribe("login-failure", function(e) {
    $("#login-message").text(loc(e.text)).css("display", "block");
  });

  //
  // Initialize:
  //

  hub.onPageLoad("login", recallMe);

  function IE8OrOlder() {
    return $('span.old-ie').length !== 0
  }

  var handleLoginSubmit = function() {
    if (IE8OrOlder()) {
      alert("Lupapiste ei tue käyttämääsi selainta, kokeile uudelleen toisella selaimella. (Esim. Mozilla Firefox, Google Chrome, Apple Safari)")
    } else {
      login();
    }
  };

  $(function() {
    recallMe();
    if (document.getElementById("login")) {
      $("#login").applyBindings({rememberMe: rememberMe, processing: processing, pending: pending, handleLoginSubmit: handleLoginSubmit});
      $("#register-button").click(function() {
        pageutil.openPage("register");
      });
    }
  });

})(jQuery);
