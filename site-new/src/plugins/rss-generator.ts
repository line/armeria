import type { LoadContext, Plugin } from '@docusaurus/types';
import path from 'path';
import fg from 'fast-glob';
import fs from 'fs/promises';
import { minimatch } from 'minimatch';

interface RssPluginOptions {
  path: string;
  title: string;
  description?: string;
  exclude?: string[];
}

interface ItemMetadata {
  title: string;
  date: Date;
  description?: string;
  url: string;
}

/**
 * Generates an RSS feed for HTML pages.
 *
 * Plugin options:
 * @param options.path - The route base path of pages to generate RSS (e.g., "/release-notes" to convert each release note)
 * @param options.title - The title of the RSS feed
 * @param options.description - The description of the RSS feed
 * @param options.exclude - Array of glob patterns for HTML files to exclude from the RSS feed (relative to options.path in build output)
 */
export default async function RssGeneratorPlugin(
  context: LoadContext,
  options: RssPluginOptions,
): Promise<Plugin<void>> {
  return {
    name: 'rss-generator-plugin',

    async postBuild({ outDir, baseUrl }) {
      const basePath = path.join(outDir, options.path);
      const siteUrl = context.siteConfig.url;

      const files = await collectHtmlFiles(basePath, options.exclude);
      const metadata = await collectMetadata(files, outDir, baseUrl, siteUrl);
      const sortedMetadata = sortMetadata(metadata);
      const rssXml = generateRssXml(sortedMetadata, options, baseUrl, siteUrl);
      await writeRssFeed(rssXml, outDir, options.path);
    },

    injectHtmlTags({ content }) {
      return {
        headTags: [
          `<link rel="alternate" type="application/rss+xml" href="${options.path}/rss.xml" title="${options.title} RSS Feed">`,
        ],
      };
    },
  };
}

/**
 * Extract date from header element for Release notes
 */
function extractDateFromReleaseNotes(html: string): Date | null {
  const dateMatch = html.match(/<header[^>]*>.*?<p>\s*<em>(.*?)<\/em>\s*<\/p>/is);
  if (!dateMatch) {
    return null;
  }

  const match = dateMatch[1].match(/(\w+)\s+(\d+),?\s+(\d{4})/);
  if (!match) {
    return null;
  }

  const [, monthStr, dayStr, yearStr] = match;
  const day = parseInt(dayStr, 10);
  const year = parseInt(yearStr, 10);

  const monthDate = new Date(`${monthStr} 1, 2000`);
  if (isNaN(monthDate.getTime())) {
    return null;
  }

  const month = monthDate.getMonth();
  return new Date(Date.UTC(year, month, day, 0, 0, 0, 0));
}

/**
 * Extract date from meta or time tags
 */
function extractDateFromMetaOrTime(html: string): Date | null {
  const metaMatch = html.match(
    /<meta\s+(?:name|property)=["'](?:article:published_time|date|pubdate)["']\s+content=["'](.*?)["']/i,
  );
  if (metaMatch) {
    return new Date(metaMatch[1]);
  }

  const timeMatch = html.match(/<time[^>]+datetime=["'](.*?)["']/i);
  return timeMatch ? new Date(timeMatch[1]) : null;
}

function extractDate(html: string): Date {
  const dateFromHeader = extractDateFromReleaseNotes(html);
  if (dateFromHeader && !isNaN(dateFromHeader.getTime())) {
    return dateFromHeader;
  }

  const dateFromMetaOrTime = extractDateFromMetaOrTime(html);
  if (dateFromMetaOrTime && !isNaN(dateFromMetaOrTime.getTime())) {
    return dateFromMetaOrTime;
  }

  return new Date();
}

function extractTitle(html: string): string {
  const titleMatch =
    html.match(/<h1[^>]*>(.*?)<\/h1>/i) ||
    html.match(/<title>(.*?)<\/title>/i);
  return titleMatch
    ? titleMatch[1].replace(/<[^>]*>/g, '').trim()
    : 'Title not found';
}

function extractDescription(html: string): string | undefined {
  // Try to extract from meta tag
  const descMatch = html.match(
    /<meta\s+name=["']description["']\s+content=["'](.*?)["']/i,
  );
  if (descMatch) {
    return descMatch[1];
  }

  // Try to extract from article content after first h2
  const articleMatch = html.match(/<article[^>]*>(.*?)<\/article>/is);
  if (articleMatch) {
    const articleContent = articleMatch[1];
    const h2Match = articleContent.match(/<h2[^>]*>.*?<\/h2>(.*)/is);
    if (h2Match) {
      const contentAfterH2 = h2Match[1]
        .replace(/<[^>]*>/g, '') // Remove HTML tags
        .replace(/\s+/g, ' ') // Normalize whitespace
        .trim();
      const maxLength = 200;
      return contentAfterH2.length > maxLength
        ? contentAfterH2.substring(0, maxLength) + '...'
        : contentAfterH2;
    }
  }

  return undefined;
}

function generateUrlFromFile(
  file: string,
  outDir: string,
  baseUrl: string,
  siteUrl: string,
): string {
  const relativePath = path.relative(outDir, file);
  const urlPath = relativePath
    .replace(/index\.html$/, '')
    .replace(/\.html$/, '');
  const fullUrl = siteUrl + `${baseUrl}${urlPath}`;
  return fullUrl.replace(/([^:])\/+/g, '$1/');
}

async function collectHtmlFiles(
  basePath: string,
  excludePatterns?: string[],
): Promise<string[]> {
  const pattern = path.join(basePath, '**', '*.html');
  let files = await fg(pattern);

  if (!excludePatterns || excludePatterns.length === 0) {
    return files;
  }

  return files.filter((file) => {
    const relativePath = path.relative(basePath, file);
    return !excludePatterns.some((pattern) => minimatch(relativePath, pattern));
  });
}

async function collectMetadata(
  files: string[],
  outDir: string,
  baseUrl: string,
  siteUrl: string,
): Promise<ItemMetadata[]> {
  return Promise.all(
    files.map(async (file) => {
      const html = await fs.readFile(file, 'utf-8');

      return {
        title: extractTitle(html),
        date: extractDate(html),
        description: extractDescription(html),
        url: generateUrlFromFile(file, outDir, baseUrl, siteUrl),
      };
    }),
  );
}

function compareVersions(a: string, b: string): number {
  const aParts = a.replace(/^v/, '').split('.').map(Number);
  const bParts = b.replace(/^v/, '').split('.').map(Number);

  for (let i = 0; i < Math.max(aParts.length, bParts.length); i += 1) {
    const aPart = aParts[i] || 0;
    const bPart = bParts[i] || 0;

    if (isNaN(aPart) || isNaN(bPart)) {
      return b.localeCompare(a);
    }

    if (aPart > bPart) return -1;
    if (aPart < bPart) return 1;
  }
  return 0;
}

/**
 * Sort metadata by date (newest first), then by title
 */
function sortMetadata(metadata: ItemMetadata[]): ItemMetadata[] {
  return [...metadata].sort((a, b) => {
    const dateDiff = b.date.getTime() - a.date.getTime();
    if (dateDiff !== 0) {
      return dateDiff;
    }
    // Try semantic version comparison, fall back to alphanumeric
    return compareVersions(a.title, b.title);
  });
}

function generateRssXml(
  items: ItemMetadata[],
  options: RssPluginOptions,
  baseUrl: string,
  siteUrl: string,
): string {
  const fullPath = siteUrl + (baseUrl + options.path);
  const channelLink = fullPath.replace(/([^:])\/+/g, '$1/');

  return `<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
    <channel>
        <title>${options.title}</title>
        <link>${channelLink}</link>
        ${options.description ? `<description>${options.description}</description>` : ''}
        <lastBuildDate>${new Date().toUTCString()}</lastBuildDate>
        <docs>https://validator.w3.org/feed/docs/rss2.html</docs>
        <language>en</language>
        <copyright>© 2015-${new Date().getFullYear()}, LY Corporation</copyright>
${items
  .map(
    (doc) => `        <item>
            <title><![CDATA[${doc.title}]]></title>
            <link>${doc.url}</link>
            <guid>${doc.url}</guid>
            <pubDate>${doc.date.toUTCString()}</pubDate>
            ${doc.description ? `<description><![CDATA[${doc.description}]]></description>` : ''}
        </item>`,
  )
  .join('\n')}
    </channel>
</rss>`;
}

async function writeRssFeed(
  rssXml: string,
  outDir: string,
  feedPath: string,
): Promise<void> {
  const rssPath = path.join(outDir, feedPath, 'rss.xml');
  await fs.mkdir(path.dirname(rssPath), { recursive: true });
  await fs.writeFile(rssPath, rssXml, 'utf-8');
}
