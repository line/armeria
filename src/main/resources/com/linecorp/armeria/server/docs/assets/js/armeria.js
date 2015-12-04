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
    specification = data;

    renderNav();
    $(window).trigger('hashchange');
  });

  $(window).on('hashchange', function () {
    render(URI(window.location.href));
  });

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
    var debugResponse = functionContainer.find('.debug-response code');

    var submitDebugRequest = function () {
      var args;
      try {
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
        contentType: TTEXT_MIME_TYPE,
        success: function (response) {
          debugResponse.text(response);
          hljs.highlightBlock(debugResponse.get(0));
          var uri = URI(window.location.href);
          var fragment = uri.fragment(true);
          fragment.setQuery('req', debugText.val());
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
      debugText.val(fragmentParams.req);
      submitDebugRequest();
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

                   return '<a href="#class/' + encodeURIComponent(className) + '" title="' + className + '">' +
                          classInfo.simpleName + '</a>';
                 });
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
