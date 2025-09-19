# Armeria website

> WARNING: This new project is now under construction, and will replace the current Gatsby project.

This directory contains the source code for the official Armeria website, built with Docusaurus.

### Installation

```
$ npm install
```

### Local Development

```
$ npm run start
```

This command starts a local development server and opens up a browser window. Most changes are reflected live without having to restart the server.

### Build

```
$ npm run build
```

This command generates static content into the `build` directory and can be served using any static contents hosting service.

### Deployment

Using SSH:

```
$ USE_SSH=true npm run deploy
```

Not using SSH:

```
$ GIT_USER=<Your GitHub username> npm run deploy
```

If you are using GitHub pages for hosting, this command is a convenient way to build the website and push to the `gh-pages` branch.

### Generating release notes

```console
$ npm run release-note <version>
```

Note that you might encounter an API rate limit exceeded error from the GitHub response.
Set `GITHUB_ACCESS_TOKEN` environment variable with the token that you have
issued in https://github.com/settings/tokens to get a higher rate limit.
