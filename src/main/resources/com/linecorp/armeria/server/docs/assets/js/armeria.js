'use strict';

$(function () {
  var TTEXT_MIME_TYPE = 'application/x-thrift; protocol=TTEXT';

  var specification = {};
  var navTemplate = Handlebars.compile($('#nav-template').html());
  var functionTemplate = Handlebars.compile($('#function-template').html());
  var classTemplate = Handlebars.compile($('#class-template').html());

  var functionContainer = $('#function');
  var classContainer = $('#class');

  var previousFragmentPath = null;

  $.getJSON('specification.json', function (data) {
    specification = escapeDocString(updateDocString(data));

    renderNav();
    $(window).trigger('hashchange');
  });

  $(window).on('hashchange', function () {
    render(URI(window.location.href));
  });

  function parseParametersDocString(docString) {
    var parameters = {};
    if (docString != null) {
      var pattern = /@param\s+(\w+)[\s\.]+(({@|[^@])*)(?=(@[\w]+|$|\s))/gm;
      var match = pattern.exec(docString);
      while (match != null) {
        parameters[match[1]] = match[2];
        match = pattern.exec(docString);
      }
    }
    return parameters;
  }

  function removeParamDocString(docString) {
    if (docString == null) {
      return null;
    }
    return docString.replace(/@param .*[\n\r]*/mig, '');
  }

  function updateDocString(specification) {
    // hierachy
    // services.SERVICE_NAME.functions.FUNCTION_NAME.docString
    // services.SERVICE_NAME.functions.FUNCTION_NAME.parameters[i].name (simple_string)
    // services.SERVICE_NAME.functions.FUNCTION_NAME.parameters[i].docString
    // classes.CLASS_NAME.docString
    // classes.CLASS_NAME.fields[i].name (simple_name)
    // classes.CLASS_NAME.fields[i].docString

    var services = specification.services;
    var classes = specification.classes;

    for (var serviceName in services) {
      var svc = services[serviceName];
      for (var functionName in svc.functions) {
        var func = svc.functions[functionName];
        var childDocStrings = parseParametersDocString(func.docString);
        func.docString = removeParamDocString(func.docString);
        for (var idx in func.parameters) {
          var param = func.parameters[idx];
          if (param.name in childDocStrings) {
            param.docString = childDocStrings[param.name];
          }
        }
      }
    }

    for (var className in classes) {
      var cls = classes[className];
      var childDocStrings = parseParametersDocString(cls.docString);
      cls.docString = removeParamDocString(cls.docString);
      for (var idx in cls.fields) {
        var field = cls.fields[idx];
        if (field.name in childDocStrings) {
          field.docString = childDocStrings[field.name];
        }
      }
    }

    return specification;
  }

  function escapeDocString(node) {
    if (node instanceof Array) {
      for (var idx in node) {
        escapeDocString(node[idx]);
      }
    } else if (node instanceof Object) {
      var docString = node['docString'];
      if (docString && typeof docString === 'string') {
        node['docString'] = escapeHtml(docString).replace(/\n/g, '<br>');
      }
      for (var key in node) {
        escapeDocString(node[key]);
      }
    }
    return node;
  }

  function render(uri) {
    var fragmentUri = uri.fragment(true);
    var path = fragmentUri.pathname();

    if (path == previousFragmentPath) {
      // Path hasn't changed so don't re-render (probably just updated link
      // for sharing debug requests).
      return;
    }
    previousFragmentPath = path;

    resetClasses();

    var hashSplit = path.split('/');
    var prefix = hashSplit[0];

    var mapping = {
      'function': function (serviceName, functionName) {
        renderFunction(serviceName, functionName);
      },

      'class': function (className) {
        renderClass(className);
      }
    };

    if (mapping[prefix]) {
      mapping[prefix].apply(this, hashSplit.slice(1));
    } else {
      renderHome();
    }
  }

  function resetClasses() {
    $('li.active').removeClass('active');
    $('.main .content').addClass('hidden');
    $('.collapse.in').collapse('hide');
  }

  function renderNav() {
    $('.sidebar').html(navTemplate({specification: specification}));
  }

  function renderHome() {
    $('#home').removeClass('hidden');
  }

  function renderFunction(serviceName, functionName) {
    var serviceInfo = specification.services[serviceName];
    if (serviceInfo == undefined) {
      return;
    }
    var functionInfo = serviceInfo.functions[functionName];
    if (functionInfo == undefined) {
      return;
    }

    processService(serviceInfo);
    processFunction(functionInfo);

    makeActive('li#nav-' + serviceName + '.' + functionName);

    // Get the elements for the HTTP headers and its stickiness before applying the template,
    // because they will be replaced when the template is applied.
    var oldDebugHttpHeadersText = functionContainer.find('.debug-http-headers');
    var oldDebugHttpHeadersSticky = functionContainer.find('.debug-http-headers-sticky');

    functionContainer.html(functionTemplate({
      'serviceName': serviceName,
      'serviceSimpleName': serviceInfo.simpleName,
      'serviceEndpoints': serviceInfo.endpoints,
      'serviceDebugPath': serviceInfo.debugPath,
      'serviceDebugFragment': serviceInfo.debugFragment,
      'function': functionInfo
    }));

    var debugHttpHeadersText = functionContainer.find('.debug-http-headers');
    var debugHttpHeadersSticky = functionContainer.find('.debug-http-headers-sticky');
    var debugText = functionContainer.find('.debug-textarea').val(functionInfo.sampleJsonRequest);
    if (oldDebugHttpHeadersSticky.is(':checked')) {
      debugHttpHeadersSticky.prop('checked', true);
      debugHttpHeadersText.val(oldDebugHttpHeadersText.val());
    } else {
      debugHttpHeadersText.val(serviceInfo.sampleHttpHeaders);
    }
    var debugResponse = functionContainer.find('.debug-response code');

    // Sets 'debug-http-headers' section
    var collapser = functionContainer.find('.debug-http-headers-collapser');
    var toggleCollapser = function() {
      collapser.toggleClass('opened');
      collapser.next().collapse('toggle');
    };
    collapser.click(function() {
      toggleCollapser();
    });
    var submitDebugRequest = function () {
      var argsText;
      var httpHeadersText = '';
      var httpHeaders = {};

      // Validate arguments.
      try {
        var args = JSON.parse(debugText.val()); // Use the JSON parser for validation.
        if (typeof args !== 'object') {
          debugResponse.text("Arguments must be a JSON object.\nYou entered: " + typeof args);
        }
        // Do not use the parsed JSON but just a minified one not to lose number precision.
        // See: https://github.com/line/armeria/issues/273
        argsText = JSON.minify(debugText.val());
      } catch (e) {
        debugResponse.text("Failed to parse a JSON object in the arguments field:\n" + e);
        return false;
      }

      // Validate HTTP headers.
      try {
        if (debugHttpHeadersText.val()) {
          httpHeaders = JSON.parse(debugHttpHeadersText.val());
          if (typeof httpHeaders !== 'object') {
            debugResponse.text("HTTP headers must be a JSON object.\nYou entered: " + typeof httpHeaders);
            return false;
          }

          httpHeadersText = JSON.minify(debugHttpHeadersText.val());
          if (httpHeadersText === '{}') {
            httpHeadersText = '';
          }
        }
      } catch (e) {
        debugResponse.text("Failed to parse a JSON object in the HTTP headers field:\n" + e);
        return false;
      }

      var method = serviceInfo.debugFragment ? serviceInfo.debugFragment + ':' + functionInfo.name
                                             : functionInfo.name;

      var request = '{"method":"' + method + '","type":"CALL","args":' + argsText + '}';
      $.ajax({
        type: 'POST',
        url: serviceInfo.debugPath,
        data: request,
        headers: httpHeaders,
        contentType: TTEXT_MIME_TYPE,
        success: function (response) {
          var result = response.length > 0 ? response : "Oneway: OK";
          debugResponse.text(result);
          hljs.highlightBlock(debugResponse.get(0));

          // Set the URL with request
          var uri = URI(window.location.href);

          // NB: Reusing the fragment object returned by URI.fragment() will cause stack overflow.
          //     Related issue: https://github.com/medialize/URI.js/issues/167
          uri.fragment(true).removeSearch('http_headers');
          uri.fragment(true).removeSearch('http_headers_sticky');
          uri.fragment(true).setSearch('args', argsText);
          if (httpHeadersText.length > 0) {
            uri.fragment(true).setSearch('http_headers', httpHeadersText);
            if (debugHttpHeadersSticky.is(':checked')) {
              uri.fragment(true).setSearch('http_headers_sticky');
            }
          }

          window.location.href = uri.toString();
        },
        error: function (jqXHR, textStatus, errorThrown) {
          debugResponse.text(errorThrown);
        }
      });
      return false;
    };
    functionContainer.find('.debug-submit').on('click', submitDebugRequest);

    functionContainer.removeClass('hidden');

    // Get the parameters ('args' and 'http_headers') from the current location.
    // Note that we do not use URI.js here to avoid parsing JSON.
    function getParameterByName(name) {
      var matches = new RegExp('[?&]' + name + '(?:=([^&]*)|&|$)').exec(window.location.href);
      if (matches) {
        if (matches[1]) {
          return decodeURIComponent(matches[1].replace(/\+/g, ' '));
        } else {
          return 'true';
        }
      }

      return undefined;
    }

    var argsText = getParameterByName('args');
    if (argsText) {
      try {
        JSON.parse(argsText);
        debugText.val(JSON.prettify(argsText));
      } catch (e) {
        // Invalid JSON
        debugText.val(argsText);
      }

      var httpHeadersText = getParameterByName('http_headers');
      if (httpHeadersText) {
        try {
          JSON.parse(httpHeadersText);
          debugHttpHeadersText.val(JSON.prettify(httpHeadersText));
        } catch (e) {
          // Invalid JSON
          debugHttpHeadersText.val(debugHttpHeadersText);
        }
      } else {
        debugHttpHeadersText.val(''); // Remove the default value if set
      }

      debugHttpHeadersSticky.prop('checked', getParameterByName('http_headers_sticky') === 'true');

      functionContainer.find('.debug-textarea').focus();
    }

    // Open debug-http-headers section when a value is set
    if (debugHttpHeadersText.val()) {
      toggleCollapser();
    }
  }

  function processService(serviceInfo) {
    var endpoints = serviceInfo.endpoints;
    for (var idx = 0; idx < endpoints.length; idx++) {
      processPath(serviceInfo, endpoints[idx]);
    }
  }

  function processPath(serviceInfo, pathInfo) {
    var availableMimeTypes = pathInfo.availableMimeTypes;
    for (var idx = 0; idx < availableMimeTypes.length; idx++) {
      var mimeType = availableMimeTypes[idx];
      if (mimeType === TTEXT_MIME_TYPE && serviceInfo.debugPath == undefined) {
        serviceInfo.debugPath = pathInfo.path;
        serviceInfo.debugFragment = pathInfo.fragment;
      }
      if (mimeType === pathInfo.defaultMimeType) {
        availableMimeTypes[idx] = '<b>' + mimeType + '</b>';
      }
    }
  }

  function processFunction(functionInfo) {
    var parameters = functionInfo.parameters;
    for (var idx = 0; idx < parameters.length; idx++) {
      processType(parameters[idx].type);
    }

    processType(functionInfo.returnType);

    var exceptions = functionInfo.exceptions;
    for (var idx = 0; idx < exceptions.length; idx++) {
      processType(exceptions[idx]);
    }
  }

  function processType(type) {
    type.typeStr = escapeTag(type.type)
        .replace(new RegExp('(?:\\w+\\.)+\\w+', 'g'),
                 function (className) {
                   var classInfo = specification.classes[className];
                   if (!classInfo) {
                     return className;
                   }

                   return '<a href="#!class/' + encodeURIComponent(className) + '" title="' + className + '">' +
                          classInfo.simpleName + '</a>';
                 });
  }

  function escapeHtml(string) {
    var htmlEscapes = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;',
      '/': '&#x2F;',
      '`': '&#x60;',
      '=': '&#x3D;'
    };
    return string.replace(/[&<>"'`=\/]/g, function(s) { return htmlEscapes[s]; });
  }

  function escapeTag(value) {
    return value.replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function makeActive(selector) {
    $(escapeSelector(selector)).addClass('active');
  }

  function escapeSelector(value) {
    return value.replace(/\./g, '\\.');
  }

  function renderClass(className) {
    var classInfo = specification.classes[className];
    if (classInfo == undefined) {
      return;
    }

    var fields = classInfo.fields;
    for (var idx = 0; idx < fields.length; idx++) {
      processType(fields[idx].type);
    }

    makeActive('li#nav-' + className);
    classContainer.html(classTemplate({'class': classInfo}));
    classContainer.removeClass('hidden');
  }
});
