# Armeria DocService client

A webapp that shows the Armeria Docs page.

## Developing

To develop, start the dev server using 

```console
$ npm install
$ npm run start
```

or with Gradle (NodeJS will be downloaded automatically)

```console
$ ./gradlew :docs-client:npm_run_start --no-daemon
```

This will usually not be useful since without a server running, the client does not have any spec it can render.
You can have server calls proxied to a running Armeria server by specifying the `ARMERIA_PORT` environment
variable, e.g.,

```console
$ ARMERIA_PORT=51234 npm run start
```

or with Gradle

```console
$ ARMERIA_PORT=51234 ./gradlew :docs-client:npm_run_start --no-daemon
```

Replacing the port of a docs page in the running server with `3000` will use the dev server to render while
proxying all server calls to the actual Armeria server.

## Checking for dependency updates

Use [npm-check-updates](https://www.npmjs.com/package/npm-check-updates)

```console
$ npx npm-check-updates
```

## Updating licenses

When changing a dependency (i.e., when the `yarn.lock` file changes), refresh license information by running

```console
$ yarn licenses generate-disclaimer --prod > ../licenses/web-licenses.txt
```
