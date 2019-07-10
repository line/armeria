from docutils.parsers.rst.roles import register_canonical_role, set_classes
from docutils.parsers.rst import directives
from docutils import nodes
from sphinx.writers.html import HTMLTranslator
from sphinx.errors import ExtensionError

import os
import re


class ApiLink(nodes.General, nodes.FixedTextElement): pass


def api_role(role, rawtext, text, lineno, inliner, options={}, content=[]):
    return api_role_internal(False, role, rawtext, text, lineno, inliner, options, content)


def apiplural_role(role, rawtext, text, lineno, inliner, options={}, content=[]):
    return api_role_internal(True, role, rawtext, text, lineno, inliner, options, content)


def api_role_internal(plural, role, rawtext, text, lineno, inliner, options, content):
    set_classes(options)

    classes = ['code', 'api-reference']

    if 'classes' in options:
        classes.extend(options['classes'])

    node = ApiLink(rawtext, text, classes=classes, api_reference=True, is_plural=plural)
    return [node], []


def visit_api_node(self, node):
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
                javadoc_mappings[simple_class_name] = \
                    os.path.relpath(dirname, javadoc_dir).replace(os.sep, '/') + '/' + basename
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
            raise ExtensionError(f'Cannot find a class from Javadoc: {text}')
        uri = javadoc_mappings[text]
        text = text.replace('$', '.')

    # Prepend the path to the Javadoc directory.
    uri = os.path.relpath(javadoc_dir, env.app.outdir).replace(os.sep, '/') + '/' + uri
    # Prepend the '@' back again if necessary
    if is_annotation:
        text = '@' + text

    # Emit the tags.
    self.body.append(self.starttag(node, 'code', suffix='', CLASS='docutils literal javadoc'))
    self.body.append(self.starttag(node, 'a', suffix='', CLASS='reference external javadoc', HREF=uri))
    self.body.append(text)
    self.body.append('</a>')
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


def depart_api_node(self, node):
    pass


def unsupported_visit_api(self, node):
    self.builder.warn('api: unsupported output format (node skipped)')
    raise nodes.SkipNode


_NODE_VISITORS = {
    'html': (visit_api_node, depart_api_node),
    'latex': (unsupported_visit_api, None),
    'man': (unsupported_visit_api, None),
    'texinfo': (unsupported_visit_api, None),
    'text': (unsupported_visit_api, None)
}


def setup(app):
    app.add_config_value('javadoc_dir', os.path.join(app.outdir, 'apidocs'), 'html')

    # Register the 'api' and 'apiplural' role.
    api_role.options = {'class': directives.class_option}
    register_canonical_role('api', api_role)
    register_canonical_role('apiplural', apiplural_role)

    # Register the node for 'api' and 'apiplural'.
    app.add_node(ApiLink, **_NODE_VISITORS)

    pass
