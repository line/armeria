from docutils.parsers.rst.roles import register_canonical_role, set_classes
from docutils.parsers.rst import directives
from docutils import nodes
from sphinx.writers.html import HTMLTranslator
from sphinx.errors import ExtensionError

import os
import re


def api_role(role, rawtext, text, lineno, inliner, options={}, content=[]):
    return api_role_internal(False, role, rawtext, text, lineno, inliner, options, content)


def apiplural_role(role, rawtext, text, lineno, inliner, options={}, content=[]):
    return api_role_internal(True, role, rawtext, text, lineno, inliner, options, content)


def api_role_internal(plural, role, rawtext, text, lineno, inliner, options, content):
    set_classes(options)

    classes = ['code', 'api-reference']

    if 'classes' in options:
        classes.extend(options['classes'])

    node = nodes.literal(rawtext, text, classes=classes, api_reference=True, is_plural=plural)
    return [node], []


def api_visit_literal(self, node, next_visitor):
    if 'api_reference' not in node.attributes:
        return next_visitor(self, node)

    env = self.builder.env
    javadoc_dir = os.path.abspath(env.config['javadoc_dir'])

    # Build the mappings from a simple class name to its Javadoc file.
    if not hasattr(env, '__javadoc_cache__'):
        env.__javadoc_mappings__ = javadoc_mappings = {}
        for dirname, subdirs, files in os.walk(javadoc_dir):
            for basename in files:
                if re.match(r'^[^A-Z]', basename) or not basename.endswith('.html'):
                    # Ignore the non-class files. We rely on the simple assumption that
                    # a class name always starts with an upper-case English alphabet.
                    continue
                simple_class_name = basename[:-5].replace('.', '$')
                javadoc_mappings[simple_class_name] = os.path.relpath(dirname, javadoc_dir) \
                                                        .replace(os.sep, '/') + '/' + basename
    else:
        javadoc_mappings = env.__javadoc_mappings__

    text = node.astext()
    if text.startswith('@'):
        text = text[1:]
        is_annotation = True
    else:
        is_annotation = False

    if text.find('.') != -1:
        # FQCN or package name.
        if re.fullmatch(r'^[^A-Z$]+$', text):
            # Package
            uri = text.replace('.', '/') + '/package-summary.html'
        else:
            # Class
            uri = text.replace('.', '/').replace('$', '.') + '.html'
            text = re.sub(r'^.*\.', '', text).replace('$', '.')
    else:
        # Simple class name; find from the pre-calculated mappings.
        if text not in javadoc_mappings:
            raise ExtensionError('Cannot find a class from Javadoc: ' + text)
        uri = javadoc_mappings[text]
        text = text.replace('$', '.')

    # Prepend the frame index.html path.
    uri = os.path.relpath(javadoc_dir, env.app.outdir).replace(os.sep, '/') + '/index.html?' + uri
    # Prepend the '@' back again if necessary
    if is_annotation:
        text = '@' + text

    # Emit the tags.
    self.body.append(self.starttag(node, 'code', suffix='', CLASS='docutils literal javadoc'))
    self.body.append(self.starttag(node, 'a', suffix='', CLASS='reference external javadoc', HREF=uri))
    self.body.append(text + '</a>')
    # Append a plural suffix.
    if node.attributes['is_plural']:
        self.body.append(self.starttag(node, 'span', suffix='', CLASS='plural-suffix'))
        if re.fullmatch(r'^.*(ch|s|sh|x|z)$', text):
            self.body.append('es')
        else:
            self.body.append('s')
        self.body.append('</span>')
    self.body.append('</code>')

    raise nodes.SkipNode


def setup(app):
    app.add_config_value('javadoc_dir', os.path.join(app.outdir, 'apidocs'), 'html')

    # Register the 'javadoc' role.
    api_role.options = {'class': directives.class_option}
    register_canonical_role('api', api_role)
    register_canonical_role('apiplural', apiplural_role)

    # Intercept the rendering of HTML literals.
    old_visitor = HTMLTranslator.visit_literal
    HTMLTranslator.visit_literal = lambda self, node: api_visit_literal(self, node, old_visitor)

    pass
