from docutils import nodes
from sphinx.writers.html import HTMLTranslator

# Applies syntax highlighting to a literal block if it has a class 'highlight-<language>'.
def parsed_literal_visit_literal_block(self, node, next_visitor):
    classes = node.get('classes', [])
    lang = ''
    for c in classes:
        if c.startswith('highlight-'):
            lang = c[10:].strip()
            break

    if len(lang) == 0:
        return next_visitor(self, node)

    def warner(msg):
        self.builder.warn(msg, (self.builder.current_docname, node.line))

    self.body.append(self.highlighter.highlight_block(node.astext(), lang, warn=warner))

    raise nodes.SkipNode


def setup(app):
    # Intercept the rendering of HTML literals.
    old_visitor = HTMLTranslator.visit_literal_block
    HTMLTranslator.visit_literal_block = lambda self, node: parsed_literal_visit_literal_block(self, node, old_visitor)

    pass
