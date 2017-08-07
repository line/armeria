'use strict';

$(function () {
  // TODO(anuraag): Set debug information in server.
  var TTEXT_MIME_TYPE = 'application/x-thrift; protocol=TTEXT';
  var UNFRAMED_GRPC_JSON_TYPE = 'application/json; charset=utf-8; protocol=gRPC';

  var specification = {};
  var navTemplate = Handlebars.compile($('#nav-template').html());
  var methodTemplate = Handlebars.compile($('#method-template').html());
  var namedTypeTemplate = Handlebars.compile($('#namedType-template').html());

  var methodContainer = $('#method');
  var namedTypeContainer = $('#namedType');

  var previousFragmentPath = null;

  $.getJSON('specification.json', function (data) {
    specification = processSpecification(data);

    renderNav();
    $(window).trigger('hashchange');
  });

  $(window).on('hashchange', function () {
    render(URI(window.location.href));
  });

  function processSpecification(specification) {
    // See ServiceSpecification.java for the structure of 'specification'
    specification = indexSpecification(specification);
    specification = setSimpleNameAndPackageName(specification);
    specification = updateDocStrings(specification);
    specification = escapeDocStrings(specification);
    return specification;
  }

  // Adds the indices that enables fast lookup.
  function indexSpecification(specification) {
    var process = function(e) {
      specification.indices.namedTypes[e.name] = e;
    };

    specification.indices = {};
    specification.indices.services = {};
    specification.indices.namedTypes = {};

    specification.services.forEach(function(e) {
      specification.indices.services[e.name] = e;
    });
    specification.enums.forEach(process);
    specification.structs.forEach(process);
    specification.exceptions.forEach(process);

    return specification;
  }

  // Sets the simple name and package name of a service or a named type.
  function setSimpleNameAndPackageName(specification) {
    var process = function(e) {
      e.simpleName = simpleName(e.name);
      e.packageName = packageName(e.name);
    };

    specification.services.forEach(process);
    specification.enums.forEach(process);
    specification.structs.forEach(process);
    specification.exceptions.forEach(process);
    return specification;
  }

  // Sets the docstrings of service method parameters or struct fields from the '@param' tags.
  function updateDocStrings(specification) {
    specification.services.forEach(function(svc) {
      svc.methods.forEach(function(m) {
        var childDocStrings = parseParamDocStrings(m.docString);
        m.docString = removeParamDocStrings(m.docString);
        m.parameters.forEach(function(p) {
          if (p.name in childDocStrings) {
            p.docString = childDocStrings[p.name];
          }
        });
      });
    });

    // TODO(trustin): Handle the docstrings of enum values.
    specification.enums.forEach(function(e) {
      e.docString = removeParamDocStrings(e.docString);
    });

    updateStructDocStrings(specification.structs);
    updateStructDocStrings(specification.exceptions);

    return specification;
  }

  function updateStructDocStrings(structs) {
    // TODO(trustin): Handle the docstrings of return values and exceptions.
    structs.forEach(function(s) {
      var childDocStrings = parseParamDocStrings(s.docString);
      s.docString = removeParamDocStrings(s.docString);
      s.fields.forEach(function(f) {
        if (f.name in childDocStrings) {
          f.docString = childDocStrings[f.name];
        }
      });
    });
  }

  function simpleName(fullName) {
    var lastDotIdx = fullName.lastIndexOf('.');
    return lastDotIdx >= 0 ? fullName.substring(lastDotIdx + 1) : fullName;
  }

  function packageName(fullName) {
    var lastDotIdx = fullName.lastIndexOf('.');
    return lastDotIdx >= 0 ? fullName.substring(0, lastDotIdx) : '';
  }

  // FIXME(trustin): Get this done on the server side.
  function parseParamDocStrings(docString) {
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

  function removeParamDocStrings(docString) {
    if (docString == null) {
      return null;
    }
    return docString.replace(/@param .*[\n\r]*/mig, '');
  }

  function escapeDocStrings(node) {
    if (node instanceof Array) {
      for (var idx in node) {
        escapeDocStrings(node[idx]);
      }
    } else if (node instanceof Object) {
      if (node.docString) {
        var docString = Handlebars.Utils.escapeExpression(node.docString);
        docString = docString.replace(/(\r\n|\n|\r)/gm, '<br>');
        node.docString = new Handlebars.SafeString(docString);
      }
      for (var key in node) {
        escapeDocStrings(node[key]);
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
      'function': function (serviceName, methodName) { // For backward compatibility
        renderMethod(serviceName, methodName);
      },
      'method': function (serviceName, methodName) {
        renderMethod(serviceName, methodName);
      },
      'namedType': function (typeName) {
        renderNamedType(typeName);
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

  function renderMethod(serviceName, methodName) {
    if (!specification.indices.services.hasOwnProperty(serviceName)) {
      return;
    }
    var serviceInfo = specification.indices.services[serviceName];

    // Do a sequential search because we did not build an index for methods.
    var methodInfo = serviceInfo.methods.find(function(m) {
      return m.name === methodName;
    });

    if (methodInfo == undefined) {
      return;
    }

    processMethod(methodInfo);

    makeActive('nav-method-' + serviceName + '/' + methodName);

    // Get the elements for the HTTP headers and its stickiness before applying the template,
    // because they will be replaced when the template is applied.
    var oldDebugHttpHeadersText = methodContainer.find('.debug-http-headers');
    var oldDebugHttpHeadersSticky = methodContainer.find('.debug-http-headers-sticky');

    methodContainer.html(methodTemplate({
      'service': serviceInfo,
      'method': methodInfo
    }));

    var debugHttpHeadersText = methodContainer.find('.debug-http-headers');
    var debugHttpHeadersSticky = methodContainer.find('.debug-http-headers-sticky');
    var debugText = methodContainer.find('.debug-textarea').val(
      Array.isArray(methodInfo.exampleRequests) ? methodInfo.exampleRequests[0] : '');

    if (oldDebugHttpHeadersSticky.is(':checked')) {
      debugHttpHeadersSticky.prop('checked', true);
      debugHttpHeadersText.val(oldDebugHttpHeadersText.val());
    } else {
      var exampleHttpHeaders = serviceInfo.exampleHttpHeaders;
      if (exampleHttpHeaders.length > 0) {
        // TODO(trustin): Allow choosing from the example list.
        debugHttpHeadersText.val(JSON.stringify(serviceInfo.exampleHttpHeaders[0], null, 2));
      } else {
        debugHttpHeadersText.val('');
      }
    }
    var debugResponse = methodContainer.find('.debug-response code');

    // Sets 'debug-http-headers' section
    var collapser = methodContainer.find('.debug-http-headers-collapser');
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
          return false;
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

      var method = methodInfo.debugFragment ? methodInfo.debugFragment + ':' + methodInfo.name
                                            : methodInfo.name;

      var request = methodInfo.debugMimeType === TTEXT_MIME_TYPE ?
                    '{"method":"' + method + '","type":"CALL","args":' + argsText + '}' :
                    argsText;
      $.ajax({
        type: 'POST',
        url: methodInfo.debugPath,
        data: request,
        beforeSend: function (xhr) {
          Object.keys(httpHeaders).forEach(function (name) {
            var values = httpHeaders[name];
            if (!Array.isArray(values)) {
              // Values is a single item, so set it directly.
              xhr.setRequestHeader(name, values);
            } else {
              xhr.setRequestHeader(name, values.join());
            }
          });
        },
        contentType: methodInfo.debugMimeType,
        dataType: 'text',
        success: function (response) {
          var result = response.length > 0 ? response : "Request sent to one-way function";
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
    methodContainer.find('.debug-submit').on('click', submitDebugRequest);

    methodContainer.removeClass('hidden');

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

      methodContainer.find('.debug-textarea').focus();
    }

    // Open debug-http-headers section when a value is set
    if (debugHttpHeadersText.val()) {
      toggleCollapser();
    }
  }

  function processMethod(methodInfo) {
    methodInfo.parameters.forEach(function(p) {
        p.typeSignatureHtml = getTypeSignatureHtml(p.typeSignature);
    });

    methodInfo.returnTypeSignatureHtml = getTypeSignatureHtml(methodInfo.returnTypeSignature);

    methodInfo.exceptionTypeSignaturesHtml = [];
    methodInfo.exceptionTypeSignatures.forEach(function(e) {
        methodInfo.exceptionTypeSignaturesHtml.push(getTypeSignatureHtml(e));
    });

    methodInfo.endpoints.forEach(function(endpoint) {
      processPath(methodInfo, endpoint);
    });
  }

  function processPath(methodInfo, endpointInfo) {
    var availableMimeTypes = endpointInfo.availableMimeTypes;
    for (var idx = 0; idx < availableMimeTypes.length; idx++) {
      var mimeType = availableMimeTypes[idx];
      if ((mimeType === TTEXT_MIME_TYPE || mimeType === UNFRAMED_GRPC_JSON_TYPE) &&
          methodInfo.debugPath == undefined) {
        methodInfo.debugPath = endpointInfo.path;
        methodInfo.debugMimeType = mimeType;
        if (mimeType === TTEXT_MIME_TYPE) {
          methodInfo.debugFormatLink =
            '<a href="https://github.com/line/armeria/blob/13b0510205a84e1a3cd17509e7d39116d050b6b3/' +
            'src/main/java/com/linecorp/armeria/common/thrift/text/TTextProtocol.java">TText</a>';
        } else if (mimeType === UNFRAMED_GRPC_JSON_TYPE) {
          methodInfo.debugFormatLink =
            '<a href="https://developers.google.com/protocol-buffers/docs/proto3#json">Protobuf</a>';
        }
        if (typeof endpointInfo.fragment === 'string') {
          methodInfo.debugFragment = endpointInfo.fragment;
        } else {
          methodInfo.debugFragment = '';
        }
      }
      if (mimeType === endpointInfo.defaultMimeType) {
        availableMimeTypes[idx] = '<b>' + mimeType + '</b>';
      }
    }
  }

  function getTypeSignatureHtml(typeSignature) {
    return escapeTag(typeSignature).replace(
      new RegExp('(?:\\w+\\.)+\\w+', 'g'),
      function (typeParamName) {
        if (!specification.indices.namedTypes.hasOwnProperty(typeParamName)) {
          return simpleName(typeParamName);
        }

        var typeParamInfo = specification.indices.namedTypes[typeParamName];
        return '<a href="#!namedType/' + encodeURIComponent(typeParamName) +
               '" title="' + typeParamName + '">' + typeParamInfo.simpleName + '</a>';
      });
  }

  function escapeTag(value) {
    return value.replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function makeActive(id) {
    $(document.getElementById(id)).addClass('active');
  }

  function escapeSelector(value) {
    return value.replace(/\./g, '\\.').replace(/[/]/g, '\\/');;
  }

  function renderNamedType(typeName) {
    if (!specification.indices.namedTypes.hasOwnProperty(typeName)) {
      return;
    }

    var info = specification.indices.namedTypes[typeName];
    if (info.hasOwnProperty('fields')) {
      info.fields.forEach(function(f) {
        f.typeSignatureHtml = getTypeSignatureHtml(f.typeSignature);
      });
    }

    makeActive('nav-namedType-' + typeName);
    namedTypeContainer.html(namedTypeTemplate({'info': info}));
    namedTypeContainer.removeClass('hidden');
  }
});
