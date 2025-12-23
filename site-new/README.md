# Armeria website

> WARNING: This new project is now under construction, and will replace the current Gatsby project.

This directory contains the source code for the official Armeria website, built with Docusaurus.

### Build requirements

- The build requirements in [CONTRIBUTING.md](https://line.github.io/armeria/community/developer-guide/#build-requirements)
- `svgbob_cli`
    - `brew install svgbob` on Mac OS X

### Working with the project

1. Download and install `node`, `npm` and other dependencies as well as
   generating the required `.json` files into the `gen-src` directory.
   ```console
   $ ../gradlew generateSiteSources
   ```
2. Install dependencies.
   ```console
   $ npm install
   ```
3. Run Docusaurus in development mode.
   ```console
   $ npm run start
   ```
   This command starts a local development server and opens up a browser window. Most changes are reflected live without having to restart the server.
   All changes will be visible at <http://localhost:3000/>.

Note that you can also use your local `npm` or `node` installation,
although you'll have to run `../gradlew generateSiteSources` to generate the `.json`
files into the `gen-src` directory at least once.

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

### Generating release notes

```console
$ npm run release-note <version>
```

Note that you might encounter an API rate limit exceeded error from the GitHub response.
Set `GITHUB_ACCESS_TOKEN` environment variable with the token that you have
issued in https://github.com/settings/tokens to get a higher rate limit.
