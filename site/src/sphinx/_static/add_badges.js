// Append the project badges at the end of the menu (sidenav).
function addBadge(parent, src, href) {
  var obj = document.createElement('object');
  obj.data = src;

  if (typeof href === 'string') {
    obj.style.pointerEvents = 'none';
    var a = document.createElement('a');
    a.href = href;
    a.target = '_blank';
    a.rel = 'noopener';
    a.appendChild(obj);
    parent.appendChild(a);
  } else {
    parent.appendChild(obj);
  }
  parent.appendChild(document.createElement('br'));
}

function addBadges(parent) {
  var li = document.createElement('li');
  li.className = 'toctree-l1';
  var div = document.createElement('div');
  div.className = 'project-badges';
  addBadge(div, 'https://img.shields.io/github/stars/line/armeria.svg?style=social');
  addBadge(div, 'https://img.shields.io/twitter/follow/armeria_project.svg?label=Follow');
  addBadge(div, 'https://img.shields.io/badge/chat-on%20slack-brightgreen.svg?style=social',
    'https://join.slack.com/t/line-armeria/shared_invite/enQtNjIxNDU1ODU1MTI2LTgwMzk0MzVhOGRhZjJiY2ExODc0MzNhYzIxZDFlYjM5OGRjNTE1MzYzYzQ4MzNhNGY2ZDM0NThhMTRmZmQ3ZjQ');
  addBadge(div, 'https://img.shields.io/travis/line/armeria/master.svg?style=flat-square',
    'https://travis-ci.org/line/armeria');
  addBadge(div, 'https://img.shields.io/appveyor/ci/line/armeria/master.svg?label=appveyor&style=flat-square',
    'https://ci.appveyor.com/project/line/armeria/branch/master');
  addBadge(div, 'https://img.shields.io/maven-central/v/com.linecorp.armeria/armeria.svg?style=flat-square',
    'https://search.maven.org/search?q=g:com.linecorp.armeria%20AND%20a:armeria');
  addBadge(div, 'https://img.shields.io/github/commit-activity/m/line/armeria.svg?style=flat-square',
    'https://github.com/line/armeria/pulse');
  addBadge(div, 'https://img.shields.io/github/issues/line/armeria/good-first-issue.svg?label=good%20first%20issues&style=flat-square');
  addBadge(div, 'https://img.shields.io/codecov/c/github/line/armeria/master.svg?style=flat-square',
    'https://codecov.io/gh/line/armeria');
  li.appendChild(div);
  parent.appendChild(li);
}

var menus = document.getElementsByClassName("wy-menu wy-menu-vertical");
if (menus.length > 0) {
  var menu = menus[0];
  var lists = menu.getElementsByTagName('ul');
  if (lists.length > 0) {
    addBadges(lists[0]);
  }
}
