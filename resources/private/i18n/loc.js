var loc;

;(function() {
  "use strict";

  function notValidLocParam(v) { return v === undefined || v === null || v === ""; }
  function joinTermArray(key) {
    if (_.isArray(key)) {
      if (_.some(key, notValidLocParam)) {
        return null;
      }
      key = key.join(".");
    }
    return key;
  }

  loc = function() {
    var args = Array.prototype.slice.call(arguments);

    var key = args[0];
    if (!key) {
      return null;
    }

    key = joinTermArray(key);
    if (!key) {
      return null;
    }

    var formatParams = args.slice(1);
    var term = loc.terms[key];

    if (term !== undefined) {
      if (!_.isEmpty(formatParams)) {
        formatParams = _.map(formatParams, String);
        for (var argIndex in formatParams) {
          term = term.replace("{" + argIndex + "}", formatParams[argIndex]);
        }
      }
    } else {
      error("Missing localization key", key);
      term = LUPAPISTE.config.mode === "dev" ? "$$NOT_FOUND$$" + key : "";
    }

    return term;
  };

  loc.supported = [];
  loc.currentLanguage = null;
  loc.allTerms = {};
  loc.terms = {};
  loc.defaultLanguage = "fi";

  loc.hasTerm = function(key) {
    return loc.terms[joinTermArray(key)] !== undefined;
  };

  function resolveLangFromUrl() {
    var url = window.parent ? window.parent.location.pathname : location.pathname;
    var langEndI = url.indexOf("/", 5);
    return langEndI > 0 ? url.substring(5, langEndI) : null;
  }

  function resolveLangFromDocument() {
    var htmlRoot = document.getElementsByTagName("html")[0];
    return htmlRoot ? htmlRoot.lang : null;
  }

  function resolveLang() {
    var langFromDocument = resolveLangFromDocument();
    var lang = langFromDocument ? langFromDocument : resolveLangFromUrl();
    return _.contains(loc.supported, lang) ? lang : loc.defaultLanguage;
  }

  loc.setLanguage = function(lang) {
    if (_.contains(loc.supported, lang)) {
      loc.currentLanguage = lang;
      loc.terms = loc.allTerms[loc.currentLanguage];
    } else {
      error("Unsupported language code", lang);
    }
  };

  loc.setTerms = function(newTerms) {
    loc.allTerms = newTerms;
    loc.supported = _.keys(newTerms);
    if (!loc.currentLanguage) {
      loc.currentLanguage = resolveLang();
    }
    loc.setLanguage(loc.currentLanguage);
  };

  loc.getErrorMessages = function() {
    var errorKeys = _.filter(_.keys(loc.terms), function(term) {
      return term.indexOf("error.") === 0;
    });

    var errorMessages = {};
    _.each(errorKeys, function(key) {
      var errorType = key.substr(6);
      errorMessages[errorType] = loc(key);
    });

    return errorMessages;
  };

  loc.getCurrentLanguage = function() { return loc.currentLanguage; };
  loc.getSupportedLanguages = function() { return loc.supported; };
  loc.getNameByCurrentLanguage = function(obj) {
    if(obj.name) {
      if(loc.currentLanguage in obj.name) {
        return obj.name[loc.currentLanguage];
      } else if(loc.defaultLanguage in obj.name) {
        return obj.name[loc.defaultLanguage];
      }
    }
    return "$$noname$$";
  };


  hub.subscribe("change-lang", function(e) {
    var lang = e.lang;
    if (_.contains(loc.supported, lang)) {
      var url = location.href.replace("/app/" + loc.currentLanguage + "/", "/app/" + lang + "/");
      window.location = url;
    }
  });

})();

