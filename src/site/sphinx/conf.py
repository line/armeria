# -*- coding: utf-8 -*-
import sys, os, re
import xml.etree.ElementTree as etree
from datetime import date
from collections import defaultdict

def etree_to_dict(t):
    t.tag = re.sub(r'\{[^\}]*\}', '', t.tag)
    d = {t.tag: {} if t.attrib else None}
    children = list(t)
    if children:
        dd = defaultdict(list)
        for dc in map(etree_to_dict, children):
            for k, v in dc.iteritems():
                dd[k].append(v)
        d = {t.tag: {k:v[0] if len(v) == 1 else v for k, v in dd.iteritems()}}
    if t.attrib:
        d[t.tag].update(('@' + k, v) for k, v in t.attrib.iteritems())
    if t.text:
        text = t.text.strip()
        if children or t.attrib:
            if text:
              d[t.tag]['#text'] = text
        else:
            d[t.tag] = text
    return d

# Parse the Maven pom.xml.
pom = etree_to_dict(etree.parse('../../../pom.xml').getroot())['project']

# Set the basic project information.
project = pom['name']
project_short = pom['name']
copyright = str(date.today().year) + ', ' + pom['organization']['name']

# Set the project version and release.
# Use the last known stable release if the current version ends with '-SNAPSHOT'.
if re.match(r'^.*-SNAPSHOT$', pom['version']):
    release = '0.23.0.Final'
    version = '0.23'
else:
    release = pom['version']
    version = re.match(r'^[0-9]+\.[0-9]+', pom['version']).group(0)

# Define some useful global substitutions.
rst_epilog = '\n'
rst_epilog += '.. |baseurl| replace:: http://line.github.io/armeria/\n'
rst_epilog += '.. |jetty_alpnAgent_version| replace:: ' + pom['properties']['jetty.alpnAgent.version'] + '\n'
rst_epilog += '.. |oss_parent_version| replace:: ' + pom['parent']['version'] + '\n'
rst_epilog += '.. |logback_version| replace:: ' + pom['properties']['logback.version'] + '\n'
rst_epilog += '.. |slf4j_version| replace:: ' + pom['properties']['slf4j.version'] + '\n'
rst_epilog += '.. |tomcat_version| replace:: ' + pom['properties']['tomcat.version'] + '\n'
rst_epilog += '\n'

needs_sphinx = '1.0'
extensions = ['sphinx.ext.autodoc']
templates_path = ['_templates']
source_suffix = '.rst'
source_encoding = 'utf-8-sig'
master_doc = 'index'
exclude_trees = ['.build']
add_function_parentheses = True
pygments_style = 'tango'
master_doc = 'index'

sys.path.append(os.path.abspath('_themes'))
html_theme = 'sphinx_rtd_theme'
html_theme_path = ['_themes']
html_short_title = project_short
html_static_path = ['_static']
html_use_smartypants = True
html_use_index = True
html_show_sourcelink = False
htmlhelp_basename = pom['artifactId']
