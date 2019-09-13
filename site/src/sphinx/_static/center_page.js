(function() {
  var head = document.head || document.getElementsByTagName('head')[0];
  var style = null;
  var mobileScreenWidth = 768;

  // Make sure this value is equal to the width of .wy-nav-content in overrides.css.
  var initialContentWidth = 960;
  // Make sure this value is equal to the width of .wy-nav-side in theme.css.
  var sideWidth = 300;
  // Keeps the current width of .wy-nav-content.
  var contentWidth = initialContentWidth;

  // Centers the page content dynamically.
  function centerPage() {
    if (style) {
      head.removeChild(style);
      style = null;
    }

    var windowWidth = window.innerWidth;
    if (windowWidth <= mobileScreenWidth) {
      return;
    }

    var leftMargin = Math.max(0, (windowWidth - sideWidth - contentWidth) / 2);
    var scrollbarWidth = document.body ? windowWidth - document.body.clientWidth : 0;
    var css = '';

    css += '.wy-nav-side { left: ' + leftMargin + 'px; }';
    css += "\n";
    css += '.wy-nav-content-wrap { margin-left: ' + (sideWidth + leftMargin) + 'px; }';
    css += "\n";
    css += '.github-corner > svg { right: ' + (leftMargin - scrollbarWidth) + 'px; }';
    css += "\n";

    var newStyle = document.createElement('style');
    newStyle.type = 'text/css';
    if (newStyle.styleSheet) {
      newStyle.styleSheet.cssText = css;
    } else {
      newStyle.appendChild(document.createTextNode(css));
    }

    head.appendChild(newStyle);
    style = newStyle;
  }

  centerPage();
  window.addEventListener('resize', centerPage);
  // Adjust the position of the GitHub Corners after document.body is available,
  // so that we can calculate the width of the scroll bar correctly.
  window.addEventListener('DOMContentLoaded', centerPage);

  // Allow a user to drag the left or right edge of the content to resize the content.
  if (interact) {
    interact('.wy-nav-content').resizable({
      edges: {left: true, right: true, bottom: false, top: false},
      modifiers: [
        interact.modifiers.restrictEdges({
          outer: 'parent',
          endOnly: true
        }),

        interact.modifiers.restrictSize({
          min: {
            width: initialContentWidth,
            height: 0
          }
        })
      ]
    }).on('resizemove', function (event) {
      var style = event.target.style;
      // Double the amount of change because the page is centered.
      contentWidth += event.deltaRect.width * 2;
      style.maxWidth = contentWidth + 'px';
      centerPage();
    });
  }
})();