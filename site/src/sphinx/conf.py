# -*- coding: utf-8 -*-
import sys, os, re
from datetime import date
from java.util import Properties
from java.io import FileInputStream

# Load the properties file.
properties = Properties()
properties.load(FileInputStream('gradle.properties'))

# Set the basic project information.
project = 'Armeria'
project_short = 'Armeria'
copyright = properties.get('inceptionYear') + '-' + str(date.today().year) + ', LINE Corporation'

# Set the project version and release.
# Use the last known stable release if the current version ends with '-SNAPSHOT'.
if re.match(r'^.*-SNAPSHOT$', properties.get('version')):
    release = '0.31.1'
else:
    release = properties.get('version')
version = re.match(r'^[0-9]+\.[0-9]+', release).group(0)

# Export the loaded properties and some useful values into epilogs
rst_epilog = '\n'
rst_epilog += '.. |baseurl| replace:: http://line.github.io/armeria/\n'
propIter = properties.entrySet().iterator()
while propIter.hasNext():
    propEntry = propIter.next()
    if propEntry.getKey() in [ 'release', 'version' ]:
        continue
    rst_epilog += '.. |' + propEntry.getKey() + '| replace:: ' + propEntry.getValue() + '\n'
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
htmlhelp_basename = project_short
