// Append the Slack invitation and project badges at the end of the menu (sidenav).
function addSlackInvitation(parent) {
  var li = document.createElement('li');
  li.className = 'toctree-l1';
  var div = document.createElement('div');
  div.className = 'slack-invitation';
  var script = document.createElement('script');
  script.type = 'text/javascript';
  script.src = 'https://slackin-line-armeria.herokuapp.com/slackin.js';
  div.appendChild(script);
  li.appendChild(div);
  parent.appendChild(li);
}

function addBadge(parent, src, href) {
  var img = document.createElement('img');
  img.src = src;
  var a = document.createElement('a');
  a.href = href;
  a.appendChild(img);
  parent.appendChild(a);
  parent.appendChild(document.createElement('br'));
}

function addBadges(parent) {
  var li = document.createElement('li');
  li.className = 'toctree-l1';
  var div = document.createElement('div');
  div.className = 'project-badges';
  addBadge(div, 'https://img.shields.io/travis/line/armeria/master.svg?style=flat-square',
    'https://travis-ci.org/line/armeria');
  addBadge(div, 'https://img.shields.io/appveyor/ci/trustin/armeria/master.svg?label=appveyor&style=flat-square',
    'https://ci.appveyor.com/project/trustin/armeria/branch/master');
  addBadge(div, 'https://img.shields.io/maven-central/v/com.linecorp.armeria/armeria.svg?style=flat-square',
    'https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.linecorp.armeria%22%20AND%20a%3A%22armeria%22');
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
    addSlackInvitation(lists[0]);
    addBadges(lists[0]);
  }
}
