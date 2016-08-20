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

    functionContainer.html(functionTemplate({
      'serviceName': serviceName,
      'serviceSimpleName': serviceInfo.simpleName,
      'serviceEndpoints': serviceInfo.endpoints,
      'serviceDebugPath': serviceInfo.debugPath,
      'function': functionInfo
    }));

    var debugText = functionContainer.find('.debug-textarea').val(functionInfo.sampleJsonRequest);
    var debugHttpHeadersText = functionContainer.find('.debug-http-headers').val(serviceInfo.sampleHttpHeaders);
    var debugResponse = functionContainer.find('.debug-response code');

    // Sets 'debug-http-headers' section
    var collapser = functionContainer.find('.debug-http-headers-collapser');
    var toggleCollapser = function() {
      collapser.toggleClass('opened');
      collapser.next().collapse('toggle');
    }
    collapser.click(function() {
      toggleCollapser();
    });
    var submitDebugRequest = function () {
      var args;
      var httpHeaders = {};
      try {
        if (debugHttpHeadersText.val()) {
          httpHeaders = JSON.parse(debugHttpHeadersText.val());
        }
        args = JSON.parse(debugText.val());
      } catch (e) {
        debugResponse.text("Failed to parse a JSON object, please check your request:\n" + e);
        return false;
      }
      var request = {
        method: functionInfo.name,
        type: 'CALL',
        args: args
      };
      $.ajax({
        type: 'POST',
        url: serviceInfo.debugPath,
        data: JSON.stringify(request),
        headers: httpHeaders,
        contentType: TTEXT_MIME_TYPE,
        success: function (response) {
          debugResponse.text(response);
          hljs.highlightBlock(debugResponse.get(0));

          // Set the URL with request
          var uri = URI(window.location.href);
          var fragment = uri.fragment(true);
          var req = {
            args: debugText.val()
          }
          if (debugHttpHeadersText.val()) { // Includes when a value is set
            req.http_headers = debugHttpHeadersText.val();
          }
          fragment.setQuery({
            req: JSON.stringify(req)
          });
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

    var fragment = URI(window.location.href).fragment(true);
    var fragmentParams = fragment.query(true);
    if (fragmentParams.req) {
      var req = JSON.parse(fragmentParams.req);

      debugText.val(req.args);

      if ('http_headers' in req) {
        debugHttpHeadersText.val(req.http_headers);
      } else {
        debugHttpHeadersText.val(''); // Remove the default value if set
      }

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
