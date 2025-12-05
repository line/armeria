import fs from 'fs/promises';
import path from 'path';

const BUILD_DIR = 'build';
const OUTPUT_FILE = `${BUILD_DIR}/rss.xml`;
const MAX_ITEMS = 150;

const feedsToMerge = [
  { path: `${BUILD_DIR}/release-notes/rss.xml`, category: 'Release Notes' },
  { path: `${BUILD_DIR}/news/rss.xml`, category: 'Newsletter' },
  { path: `${BUILD_DIR}/blog/rss.xml`, category: 'Blog' },
];

function extractItems(feedContent: string): string[] {
  const itemsMatch = feedContent.match(/<item>.*?<\/item>/gs);
  return itemsMatch || [];
}

function addCategoryToItem(item: string, category: string): string {
  return item.replace(
    /(<item>\s*)(<\w+>)/,
    `$1<category>${category}</category>\n            $2`,
  );
}

function sortByPubDate(items: string[]): string[] {
  return items.sort((a, b) => {
    const pubDateA = a.match(/<pubDate>(.*?)<\/pubDate>/)?.[1];
    const pubDateB = b.match(/<pubDate>(.*?)<\/pubDate>/)?.[1];

    if (!pubDateA) return 1;
    if (!pubDateB) return -1;

    return new Date(pubDateB).getTime() - new Date(pubDateA).getTime();
  });
}

function generateRssFeed(items: string[]): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
    <channel>
        <title>Armeria</title>
        <link>https://armeria.dev</link>
        <lastBuildDate>${new Date().toUTCString()}</lastBuildDate>
        <docs>https://validator.w3.org/feed/docs/rss2.html</docs>
        <language>en</language>
        <copyright>© 2015-${new Date().getFullYear()}, LY Corporation</copyright>
        ${items.join('\n        ')}
    </channel>
</rss>`;
}

async function collectFeedItems(
  feeds: Array<{ path: string; category: string }>,
): Promise<string[]> {
  const feedItems: string[] = [];

  for (const feed of feeds) {
    const fullPath = path.join(process.cwd(), feed.path);
    try {
      const feedContent = await fs.readFile(fullPath, 'utf-8');
      const items = extractItems(feedContent);
      const itemsWithCategory = items.map((item) =>
        addCategoryToItem(item, feed.category),
      );
      feedItems.push(...itemsWithCategory);
    } catch (error) {
      console.warn(`Failed to read feed: ${feed.path}`, error);
      continue;
    }
  }

  return feedItems;
}

async function writeFeedToFile(
  feedContent: string,
  outputFile: string,
): Promise<void> {
  const outputPath = path.join(process.cwd(), outputFile);
  await fs.writeFile(outputPath, feedContent, 'utf-8');
  console.log(`Merged feed written to ${outputPath}`);
}

export default async function main() {
  const feedItems = await collectFeedItems(feedsToMerge);
  const sortedFeedItems = sortByPubDate(feedItems);
  const limitedFeedItems = sortedFeedItems.slice(0, MAX_ITEMS);

  const mergedFeed = generateRssFeed(limitedFeedItems);

  await writeFeedToFile(mergedFeed, OUTPUT_FILE);
}

main();
