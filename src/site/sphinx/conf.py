# -*- coding: utf-8 -*-
import sys, os, re
import xml.etree.ElementTree as etree
from datetime import date

# Parse the Maven pom.xml.
ns = { 'mvn': 'http://maven.apache.org/POM/4.0.0' }
pom = etree.parse('../../../pom.xml')
pom.artifactId = pom.find('mvn:artifactId', ns).text
pom.version = pom.find('mvn:version', ns).text
pom.name = pom.find('mvn:name', ns).text
pom.organization = lambda: None
pom.organization.name = pom.find('mvn:organization/mvn:name', ns).text
pom.organization.url = pom.find('mvn:organization/mvn:url', ns).text

# Set the basic project information.
project = pom.name
project_short = pom.name
copyright = str(date.today().year) + ', ' + pom.organization.name

# Set the project version and release.
# Use the last known stable release if the current version ends with '-SNAPSHOT'.
if re.match(r'^.*-SNAPSHOT$', pom.version):
    release = '0.4.0.Final'
    version = '0.4'
else:
    release = pom.version
    version = re.match(r'^[0-9]+\.[0-9]+', pom.version).group(0)

# Define some useful global substitutions.
rst_epilog = '\n'
rst_epilog += '.. |baseurl| replace:: http://line.github.io/armeria/\n'
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
htmlhelp_basename = pom.artifactId
