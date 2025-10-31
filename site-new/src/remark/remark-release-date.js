import { visit } from 'unist-util-visit';

/**
 * A remark plugin that adds a formatted date in release note files
 * based on the frontmatter date.
 */
// eslint-disable-next-line no-unused-vars
const plugin = (options) => {
  const transformer = (markdownAST, vfile) => {
    // Only process release note files (exclude index files)
    const filePath = vfile.path || vfile.filename || '';
    const isReleaseNote =
      filePath.includes('release-notes/') && !filePath.includes('index-');

    if (!isReleaseNote) {
      return;
    }

    // Get the date from frontmatter
    const frontmatter = vfile.data?.frontMatter;
    if (!frontmatter?.date) {
      return;
    }

    let dateAdded = false;
    let firstHeadingFound = false;

    visit(markdownAST, (node, index, parent) => {
      // Find the first heading (h1)
      if (node.type === 'heading' && node.depth === 1 && !firstHeadingFound) {
        firstHeadingFound = true;

        // Only add date if not already added
        if (!dateAdded && parent && typeof index === 'number') {
          const date = new Date(frontmatter.date);
          const formattedDate = date.toLocaleDateString('en-US', {
            year: 'numeric',
            month: 'long',
            day: 'numeric',
          });

          // Create a paragraph node with the formatted date
          const dateNode = {
            type: 'paragraph',
            children: [
              {
                type: 'emphasis',
                children: [
                  {
                    type: 'text',
                    value: `${formattedDate}`,
                  },
                ],
              },
            ],
          };

          // Insert the date paragraph after the heading
          parent.children.splice(index + 1, 0, dateNode);
          dateAdded = true;
        }
      }
    });
  };

  return transformer;
};

export default plugin;
