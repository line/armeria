.. _server-http-file:

Serving static files
====================

.. note::

    Visit `armeria-examples <https://github.com/line/armeria-examples>`_ to find a fully working example.

Use :api:`FileService` to serve static files under a certain directory. :api:`FileService` supports
``GET`` and ``HEAD`` HTTP methods and will auto-fill ``Date``, ``Last-Modified``, ``ETag`` and auto-detected
``Content-Type`` headers for you. It is also capable of sending a ``304 Not Modified`` response based on
``If-None-Match`` and ``If-Modified-Since`` header values.

.. code-block:: java

    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.file.FileService;

    ServerBuilder sb = Server.builder();
    sb.serviceUnder("/images/",
                    FileService.of(Paths.get("/var/lib/www/images"));

    // You can also serve the resources in the class path.
    sb.serviceUnder("/resources",
                    FileService.of(ClassLoader.getSystemClassLoader(),
                                   "/com/example/resources"));

Auto-generating directory listings
----------------------------------

You can configure :api:`FileService` to generate the directory listing of the directories without
an ``index.html`` file using the ``autoIndex(boolean)`` method in :api:`FileServiceBuilder`.

.. code-block:: java

    import com.linecorp.armeria.server.file.FileServiceBuilder;

    FileServiceBuilder fsb =
            FileService.builder(Paths.get("/var/lib/www/images"));

    // Enable auto-index.
    fsb.autoIndex(true);

    FileService fs = fsb.build();

.. note::

   Be careful when you enable this feature in production environment; consider its security implications.

Specifying additional response headers
--------------------------------------

You can specify additional response headers such as ``cache-control`` and other custom headers.

.. code-block:: java

    import com.linecorp.armeria.common.ServerCacheControl;
    import com.linecorp.armeria.common.ServerCacheControlBuilder;

    FileServiceBuilder fsb =
            FileService.builder(Paths.get("/var/lib/www/images"));

    // Specify cache control directives.
    ServerCacheControl cc = new ServerCacheControlBuilder()
            .maxAgeSeconds(86400)
            .cachePublic()
            .build();
    fsb.cacheControl(cc /* "max-age=86400, public" */);

    // Specify a custom header.
    fsb.setHeader("foo", "bar");

    FileService fs = fsb.build();

Adjusting static file cache
---------------------------

By default, :api:`FileService` caches up to 1024 files whose length is less than or equal to
65,536 bytes. You can customize this behavior using :api:`FileServiceBuilder`.

.. code-block:: java

    FileServiceBuilder fsb =
            FileService.builder(Paths.get("/var/lib/www/images"));

    // Cache up to 4096 files.
    fsb.entryCacheSpec("maximumSize=4096");
    // Cache files whose length is less than or equal to 1 MiB.
    fsb.maxCacheEntrySizeBytes(1048576);

    FileService fs = fsb.build();

The cache can be disabled by specifying ``0`` for ``maxCacheEntries()``.
You can also specify a custom cache specification using ``entryCacheSpec()``,
as defined in `Caffeine documentation <https://static.javadoc.io/com.github.ben-manes.caffeine/caffeine/2.8.0/com/github/benmanes/caffeine/cache/CaffeineSpec.html>`_.
Or, you can override the default cache specification of ``maximumSize=1024`` using
the JVM property ``-Dcom.linecorp.armeria.fileServiceCache=<spec>``.

Serving pre-compressed files
----------------------------

:api:`FileService` can be configured to serve a pre-compressed file based on the value of the
``Accept-Encoding`` header. For example, if a client sent the following HTTP request:

.. code-block:: http

    GET /index.html HTTP/1.1
    Host: example.com
    Accept-Encoding: gzip, identity

:api:`FileService` could look for ``/index.html.gz`` first and send the following response with the
``Content-Encoding: gzip`` header if it exists:

.. code-block:: http

    HTTP/1.1 200 OK
    Host: example.com
    Content-Encoding: gzip
    Content-Type: text/html
    ...

    <compressed content>

If ``/index.html.gz`` does not exist but ``/index.html`` does, it would fall back on serving the uncompressed
content:

.. code-block:: http

    HTTP/1.1 200 OK
    Host: example.com
    Content-Type: text/html
    ...

    <uncompressed content>

This behavior is enabled by calling ``serveCompressedFiles(true)`` for :api:`FileServiceBuilder`.
``.gz`` (gzip) and ``.br`` (Brotli) files are supported currently.

.. code-block:: java

    FileServiceBuilder fsb =
            FileService.builder(ClassLoader.getSystemClassLoader(),
                                "/com/example/resources");

    // Enable serving pre-compressed files.
    fsb.serveCompressedFiles(true);

    FileService fs = fsb.build();

Serving an individual file
--------------------------

You can also serve an individual file using :api:`HttpFile`. Like :api:`FileService` does, :api:`HttpFile`
supports ``GET`` and ``HEAD`` HTTP methods and will auto-fill ``Date``, ``Last-Modified``, ``ETag`` and
auto-detected ``Content-Type`` headers for you. It is also capable of sending a ``304 Not Modified`` response
based on ``If-None-Match`` and ``If-Modified-Since`` header values.

.. code-block:: java

    import com.linecorp.armeria.server.file.HttpFile;

    HttpFile favicon = HttpFile.of(Paths.get("/var/lib/www/favicon.ico"));

    ServerBuilder sb = Server.builder();
    // Serve the favicon.ico file by converting an HttpFile into a service.
    sb.service("/favicon.ico", favicon.asService());

For instance, it is possible to serve the same file (e.g. ``index.html``) for all requests under a certain
path, which is useful when serving a frontend application with client-side routing.

.. code-block:: java

    HttpFile index = HttpFile.of(Paths.get("/var/lib/www/index.html"));

    ServerBuilder sb = Server.builder();
    // Register the file service for assets.
    sb.serviceUnder("/node_modules",
                    FileService.of(Paths.get("/var/lib/www/node_modules")));
    sb.serviceUnder("/static",
                    FileService.of(Paths.get("/var/lib/www/static")));
    // Register the fallback file service.
    sb.serviceUnder("/", index.asService());

You can also achieve the same behavior using :ref:`server-annotated-service`:

.. code-block:: java

    // Register the fallback file service.
    sb.annotatedService(new Object() {
        final HttpFile index = HttpFile.of(Paths.get("/var/lib/www/index.html"));
        @Get
        @Head
        @Path("glob:/**")
        public HttpResponse getIndex(ServiceRequestContext ctx, HttpRequest req) {
            return index.asService().serve(ctx, req);
        }
    });

Configuring ``HttpFile``
------------------------

An :api:`HttpFile` can be configured to send different headers than the auto-filled ones using
:api:`HttpFileBuilder`. For example, you can:

- Disable auto-generation of ``Date``, ``Last-Modified``, ``Content-Type`` and ``ETag`` header.
- Customize how ``ETag`` is calculated from metadata.
- Add or set additional custom HTTP headers.

.. code-block:: java

    import com.linecorp.armeria.server.file.HttpFile;
    import com.linecorp.armeria.server.file.HttpFileBuilder;

    HttpFileBuilder fb = HttpFile.builder(Paths.get("/var/lib/www/index.html"));
    // Disable the 'Date' header.
    fb.date(false);
    // Disable the 'Last-Modified' header.
    fb.lastModified(false);
    // Disable the 'ETag' header.
    fb.entityTag(false);
    // Disable the 'Content-Type' header.
    fb.autoDetectContentType(false);
    // Set the 'Content-Type' header manually.
    fb.contentType("text/html; charset=EUC-KR");
    // Set the 'Cache-Control' header.
    fb.cacheControl(ServerCacheControl.REVALIDATED /* "no-cache" */);
    // Set a custom header.
    fb.setHeader("x-powered-by", "Armeria");
    HttpFile f = fb.build();

Caching ``HttpFile``
--------------------

Unlike :api:`FileService`, :api:`HttpFile` does not cache the file content. Use ``HttpFile.ofCached()``
to enable content caching for an existing :api:`HttpFile`:

.. code-block:: java

    HttpFile uncachedFile = HttpFile.of(Paths.get("/var/lib/www/index.html"));
    HttpFile cachedFile = HttpFile.ofCached(uncachedFile, 65536);

Note that you need to specify the maximum allowed length of the cached content. In the above example, the file
will not be cached if the length of the file exceeds 65,536 bytes.

Aggregating ``HttpFile``
------------------------

An :api:`HttpFile` usually does not store its content in memory but reads its content on demand, allowing you
to stream a potentially very large file. If you want to ensure the content of the file is kept in memory so
that file I/O does not occur on each retrieval, you can use the ``aggregate()`` method:

.. code-block:: java

    // You need to prepare an Executor which will be used for reading the file,
    // because file I/O is often a blocking operation.
    Executor ioExecutor = ...;

    HttpFile file = HttpFile.of(Paths.get("/var/lib/www/img/logo.png");
    CompletableFuture<AggregatedHttpFile> future = file.aggregate(ioExecutor);
    AggregatedHttpFile aggregated = future.join();

    // Note that AggregatedHttpFile is a subtype of HttpFile.
    assert aggregated instanceof HttpFile;

    // The content of the file can now be retrieved from memory.
    HttpData content = aggregated.content();

Note that an aggregated :api:`HttpFile` is not linked in any way from the :api:`HttpFile` it was aggregated
from, which means the content and attributes of the aggregated :api:`HttpFile` does not change when the original
:api:`HttpFile` changes. Use ``HttpFile.ofCached()`` instead if such behavior is necessary.

Building ``AggregatedHttpFile`` from ``HttpData``
-------------------------------------------------

The content you need to serve is not always from an external resource but sometimes from memory, such as
``byte[]`` or ``String``. Use ``HttpFile.of(HttpData)`` or ``HttpFile.builder(HttpData)`` to build an
``AggregatedHttpFile`` from an in-memory resource:

.. code-block:: java

    // Build from a byte array.
    AggregatedHttpFile f1 = HttpFile.of(HttpData.of(new byte[] { 1, 2, 3, 4 }));

    // Build from a String.
    AggregatedHttpFile f2 = HttpFile.of(HttpData.ofUtf8("Hello, world!"));

    // Build using a builder with downcast.
    // Note: HttpFileBuilder.build() returns an AggregatedHttpFile
    //       if HttpFileBuilder was created from an HttpData.
    AggregatedHttpFile f3 =
        (AggregatedHttpFile) HttpFile.builder(HttpData.ofAscii("Armeria"))
                                     .lastModified(false)
                                     .build();
