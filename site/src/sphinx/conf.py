# -*- coding: utf-8 -*-
import sys, os, re, yaml
from datetime import date

def load_properties(filepath, sep='=', comment_char='#'):
    """
    Read the file passed as parameter as a properties file.
    """
    props = {}
    with open(filepath, 'rt') as f:
        for line in f:
            l = line.strip()
            if l and not l.startswith(comment_char):
                key_value = l.split(sep)
                key = key_value[0].strip()
                value = sep.join(key_value[1:]).strip().strip('"')
                props[key] = value
    return props

def load_yaml(filepath):
    with open(filepath, 'r') as f:
        return yaml.load(f, Loader=yaml.FullLoader)

# Load the gradle.properties and dependencies.yml.
rootDir = os.path.dirname(os.path.abspath(__file__)) + '/../../..'
properties = load_properties(rootDir + '/gradle.properties')
dependencies = load_yaml(rootDir + '/dependencies.yml')

# Set the basic project information.
project = 'Armeria'
project_short = 'Armeria'
copyright = properties['inceptionYear'] + '-' + str(date.today().year) + ', LINE Corporation'

# Set the project version and release.
# Use the last known stable release if the current version ends with '-SNAPSHOT'.
release = properties['version']
version = re.match(r'^[0-9]+\.[0-9]+', release).group(0)

# Export the loaded properties and some useful values into epilogs
rst_epilog = '\n'
rst_epilog += '.. |baseurl| replace:: https://line.github.io/armeria/\n'
for k in properties.keys():
    v = properties[k]
    if k in [ 'release', 'version' ]:
        continue
    rst_epilog += '.. |' + k + '| replace:: ' + v + '\n'
for groupId in dependencies.keys():
    if groupId == 'boms':
        for gav in dependencies['boms']:
            k = re.sub(':[^:]+$', '', gav, 1) + ':version'
            v = re.sub('[^:]+:', '', gav, 2)
            rst_epilog += '.. |' + k + '| replace:: ' + v + '\n'
    else:
        for artifactId in dependencies[groupId]:
            if 'version' in dependencies[groupId][artifactId]:
                k = groupId + ':' + artifactId + ':version'
                v = dependencies[groupId][artifactId]['version']
                rst_epilog += '.. |' + k + '| replace:: ' + v + '\n'
rst_epilog += '\n'

needs_sphinx = '1.0'
sys.path.append(os.path.abspath('_extensions'))
extensions = ['sphinx.ext.autodoc', 
              'sphinxcontrib.httpdomain', 
              'sphinxcontrib.plantuml',
              'api', 'highlightjs']
templates_path = ['_templates']
source_suffix = '.rst'
source_encoding = 'utf-8-sig'
master_doc = 'index'
exclude_trees = ['.build']
add_function_parentheses = True

html_theme = 'sphinx_rtd_theme'
html_theme_path = ['_themes']
html_short_title = project_short
html_static_path = ['_static']
html_favicon = 'logo.png'
html_use_index = True
html_show_sourcelink = False
htmlhelp_basename = project_short

# sphinxcontrib-plantuml options
plantuml = os.getenv('plantuml')

# sphinxcontrib-inlinesyntaxhighlight options
inline_highlight_literals = False
