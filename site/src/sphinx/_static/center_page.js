// Center the page dynamically.
function centerPage() {
  // Unhide the page hidden by the cloak in the _static/overrides.css.
  document.body.style.display = 'block';

  var side = document.getElementsByClassName('wy-nav-side')[0];
  var content = document.getElementsByClassName('wy-nav-content-wrap')[0];
  var ribbon = document.getElementsByClassName('github-fork-ribbon-wrapper')[0];

  if (typeof side !== 'object' || typeof content !== 'object' || typeof ribbon !== 'object') {
    return;
  }

  var windowWidth = window.innerWidth;
  var scrollbarWidth = windowWidth - document.body.clientWidth;
  var sideWidth = 300;
  var contentWidth = 800;

  if (windowWidth > 768) {
    var leftMargin = Math.max(0, (windowWidth - sideWidth - contentWidth) / 2);
    side.style.left = leftMargin + 'px';
    content.style.marginLeft = (300 + leftMargin) + 'px';
    ribbon.style.marginRight = (leftMargin - scrollbarWidth) + 'px';
  } else {
    side.removeAttribute('style');
    content.removeAttribute('style');
    ribbon.removeAttribute('style');
  }
}

window.onresize = centerPage;
centerPage();
