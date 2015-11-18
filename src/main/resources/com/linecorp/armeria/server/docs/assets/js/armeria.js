'use strict';

$(function () {
  var specification = {};
  var navTemplate = Handlebars.compile($('#nav-template').html());
  var functionTemplate = Handlebars.compile($('#function-template').html());
  var classTemplate = Handlebars.compile($('#class-template').html());

  var functionContainer = $('#function');
  var classContainer = $('#class');

  $.getJSON('specification.json', function (data) {
    specification = data;

    renderNav();
    $(window).trigger('hashchange');
  });

  $(window).on('hashchange', function () {
    render(window.location.hash);
  });

  function render(hash) {
    resetClasses();

    var hashSplit = hash.split('/');
    var prefix = hashSplit[0];

    var mapping = {
      '#function': function (serviceName, functionName) {
        renderFunction(serviceName, functionName);
      },

      '#class': function (className) {
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

    processFunction(functionInfo);

    makeActive('li#nav-' + serviceName + '.' + functionName);

    functionContainer.html(functionTemplate({
      'serviceName': serviceName,
      'serviceSimpleName': serviceInfo.simpleName,
      'function': functionInfo}
    ));

    functionContainer.removeClass('hidden');
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
