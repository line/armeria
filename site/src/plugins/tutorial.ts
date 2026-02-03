import fs from 'fs';
import path from 'path';
import matter from 'gray-matter';
import type { LoadContext, Plugin } from '@docusaurus/types';

export default function TutorialPlugin(context: LoadContext): Plugin<void> {
  const metadata: Record<string, { menuTitle: string; order: number }> = {};

  return {
    name: 'tutorial-plugin',
    async loadContent() {
      const docsDir = path.join(context.siteDir, 'src/content/docs/tutorials');

      // Recursively collect Markdown files
      const collectMarkdownFiles = (dir: string): string[] => {
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        const files = entries.flatMap((entry) => {
          const fullPath = path.join(dir, entry.name);
          return entry.isDirectory()
            ? collectMarkdownFiles(fullPath)
            : fullPath;
        });
        return files.filter(
          (file) => file.endsWith('.md') || file.endsWith('.mdx'),
        );
      };

      const markdownFiles = collectMarkdownFiles(docsDir);

      markdownFiles.forEach((fullPath) => {
        const content = fs.readFileSync(fullPath, 'utf8');
        const { data: frontMatter } = matter(content);
        const { sidebar_label: sidebarLabel } = frontMatter;

        const relativePath = path.relative(context.siteDir, fullPath);

        // Store metadata for tutorial steps if present
        if (sidebarLabel) {
          const match = sidebarLabel.match(/^(\d+)\.\s*(.+)$/);
          if (match) {
            const order: number = parseInt(match[1], 10);
            const menuTitle: string = match[2];
            metadata[relativePath] = { menuTitle, order };
          }
        }
      });
    },
    async contentLoaded({ actions }) {
      const { setGlobalData } = actions;
      setGlobalData(metadata);
    },
  };
}
