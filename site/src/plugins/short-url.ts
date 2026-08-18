import type { LoadContext, Plugin } from '@docusaurus/types';

interface ShortUrl {
  name: string;
  href: string;
}

interface ShortUrlPluginOptions {
  shortUrls: ShortUrl[];
}

export default function shortUrlPlugin(
  context: LoadContext,
  options: ShortUrlPluginOptions,
): Plugin<void> {
  return {
    name: 'short-url-plugin',
    async contentLoaded({ actions }) {
      const { createData, addRoute } = actions;
      const shortUrls: ShortUrl[] = options.shortUrls || [];

      for (const entry of shortUrls) {
        // Create a JSON data file with the href
        // eslint-disable-next-line no-await-in-loop
        const dataPath = await createData(
          `short-url-${entry.name}.json`,
          JSON.stringify({ href: entry.href }),
        );

        // Add a route for the short URL which uses the data file as a prop
        addRoute({
          path: `/s/${entry.name}`,
          component: '@site/src/components/short-url-redirect',
          modules: {
            pluginData: dataPath,
          },
          exact: true,
        });
      }
    },
  };
}
