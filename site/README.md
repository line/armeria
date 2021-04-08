This directory contains a Gatsby project that generates the official
web site for Armeria.

### Build requirements

- The build requirements in [CONTRIBUTING.md](../CONTRIBUTING.md)
- `svgbob_cli`
  - `brew install rust && cargo install svgbob_cli` on Mac OS X

### Working with the project

1. Download and install `node`, `npm` and other dependencies as well as
   generating the required `.json` files into the `gen-src` directory.
   ```console
   $ ../gradlew generateSiteSources
   ```
2. Run Gatsby in development mode.
   ```console
   $ ../gradlew develop
   ```
   or
   ```console
   $ npm run develop
   ```
3. Start updating the pages in `src/pages`.
   All changes will be visible at <http://127.0.0.1:8000/>.

Note that you can also use your local `npm` or `node` installation,
although you'll have to run `../gradlew generateSources` to generate the `.json`
files into the `gen-src` directory at least once.

### Adding a short URL

It's often useful to define a short URL such as `/s/slack` when you need
to:

- Shorten a long URL
- Manage a URL that changes often

Define a short URL in `gatsby-config.js`. `createPages` in `gatsby-node.js`
will create static redirect pages for it. For example, the following
configuration will create a redirect from `/s/foo` to `https://bar.com/`:

```js
module.exports = {
  siteMetadata: {
    shortUrls: [
      {
        name: 'foo',
        href: 'https://bar.com/',
      },
    ],
  },
  // ...
};
```

### Building the project for deployment

1. Perform a clean production build.
   ```console
   $ ../gradlew clean site
   ```
2. Upload all files in the `public` directory into the `gh-pages` branch, e.g.
   ```console
   $ cd ../../site-armeria
   $ rm -fr *
   $ mv ../../armeria/site/public/* .
   $ git add -A .
   $ git commit --amend -m 'Deploy the web site'
   $ git push --force
   ```

### Checking for dependency updates

Use [npm-check-updates](https://www.npmjs.com/package/npm-check-updates)

```console
$ npx npm-check-updates --target latest
```

### Checking what's taking space in `.js` bundles

Make sure the resource or component you're adding does not increase the
bundle size too much. You can check which component or page is taking
how much space using `source-map-explorer`.

1. Run `source-map` task using `npm`.
   ```console
   $ npm run source-map
   ```
2. A web browser will show a tree map.
   See [here](https://github.com/danvk/source-map-explorer#readme) to
   learn more about how to interpret the report.
