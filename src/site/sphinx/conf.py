# -*- coding: utf-8 -*-
import sys, os
from datetime import date

project = 'Armeria'
project_short = 'Armeria'
project_basename = 'armeria'
copyright = str(date.today().year) + ', LINE Corporation'
version = '0.4'
release = '0.4.0.Final'

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
htmlhelp_basename = project_basename
