# Armeria DocService client

A webapp that shows the Armeria Docs page.

## Developing

To develop, start the dev server using 

```console
$ npm install
$ npm run develop
```

or with Gradle (NodeJS will be downloaded automatically)

```console
$ ./gradlew :docs-client:npm_run_develop --no-daemon
```

This will usually not be useful since without a server running, the client does not have any spec it can render.
You can have server calls proxied to a running Armeria server by specifying the `ARMERIA_PORT` environment
variable, e.g.,

```console
$ ARMERIA_PORT=51234 npm run develop
```

or with Gradle

```console
$ ARMERIA_PORT=51234 ./gradlew :docs-client:npm_run_start --no-daemon
```

Replacing the port of the docs page in the running server with `3000` will use the dev server to render while
proxying all server calls to the actual Armeria server.

## Checking for dependency updates

Use [npm-check-updates](https://www.npmjs.com/package/npm-check-updates)

```console
$ npx npm-check-updates --target latest
```

## Updating licenses

Make sure to include the `../licenses/web-licenses.txt` file in your commit if it is updated automatically
during the build process (`npm run build`).
