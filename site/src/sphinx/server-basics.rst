.. _`a name-based virtual host`: https://en.wikipedia.org/wiki/Virtual_hosting#Name-based
.. _LoggingService: apidocs/index.html?com/linecorp/armeria/server/logging/LoggingService.html
.. _ServerBuilder: apidocs/index.html?com/linecorp/armeria/server/ServerBuilder.html
.. _VirtualHost: apidocs/index.html?com/linecorp/armeria/server/VirtualHost.html
.. _VirtualHostBuilder: apidocs/index.html?com/linecorp/armeria/server/VirtualHostBuilder.html

.. _server-basics:

Server basics
=============

To start a server, you need to build it first. Use `ServerBuilder`_:

.. code-block:: java

    import com.linecorp.armeria.server.Server;
    import com.linecorp.armeria.server.ServerBuilder;

    ServerBuilder sb = new ServerBuilder();
    // TODO: Configure your server here.
    Server server = sb.build();
    server.start();

Ports
-----

To serve anything, you need to specify which TCP/IP port you want to bind onto:

.. code-block:: java

    import static com.linecorp.armeria.common.SessionProtocol.HTTP;

    ServerBuilder sb = new ServerBuilder();
    // Configure an HTTP port.
    sb.port(8080, HTTP);
    // TODO: Add your services here.
    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();

Services
--------

Even if we opened a port, it's of no use if we didn't bind any services to them. Let's add some:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponseWriter;
    import com.linecorp.armeria.common.HttpStatus;
    import com.linecorp.armeria.common.MediaType;

    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.AbstractHttpService;

    ServerBuilder sb = new ServerBuilder();
    sb.port(8080, HTTP);

    // Add a simple 'Hello, world!' service.
    sb.service("/", new AbstractHttpService() {
        @Override
        protected void doGet(ServiceRequestContext ctx,
                             HttpRequest req, HttpResponseWriter res) {
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, world!");
        }
    });

    // Using path variables:
    sb.service("/greet/{name}", new AbstractHttpService() {
        @Override
        protected void doGet(ServiceRequestContext ctx,
                             HttpRequest req, HttpResponseWriter res) {
            String name = ctx.pathParam("name");
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
        }
    }.decorate(LoggingService.newDecorator()); // Enable logging

    // Using an annotated service object:
    sb.annotatedService(new Object() {
        @Get("/greet2/{name}")
        public HttpResponse greet(@Param("name") String name) {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
        }
    });

    // Just in case your name contains a slash:
    sb.serviceUnder("/greet3", new AbstractHttpService() {
        @Override
        protected void doGet(ServiceRequestContext ctx,
                             HttpRequest req, HttpResponseWriter res) {
            String path = ctx.pathWithoutPrefix();  // Get the path without the prefix ('/hello')
            String name = path.substring(1); // Strip the leading slash ('/')
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
        }
    });

    // Using an annotated service object:
    sb.annotatedService(new Object() {
        @Get("regex:^/greet4/(?<name>.*)$")
        public HttpResponse greet(@Param("name") String name) {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
        }
    });

    // Using a query parameter (e.g. /greet5?name=alice) on an annotated service object:
    sb.annotatedService(new Object() {
        @Get("/greet5")
        public HttpResponse greet(@Param("name") String name,
                                  @Param("title") @Optional("Mr.") String title) {
            // "Mr." is used by default if there is no title parameter in the request.
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s %s!", title, name);
        }
    });

    // Getting a map of query parameters on an annotated service object:
    sb.annotatedService(new Object() {
        @Get("/greet6")
        public HttpResponse greet(HttpParameters parameters) {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!",
                                   parameters.get("name");
    });

    // Using media type negotiation:
    sb.annotatedService(new Object() {
        @Get("/greet7")
        @ProduceType("application/json;charset=UTF-8")
        public HttpResponse greetGet(@Param("name") String name) {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"name\":\"%s\"}", name);
        }

        @Post("/greet7")
        @ConsumeType("application/x-www-form-urlencoded")
        public HttpResponse greetPost(@Param("name") String name) {
            return HttpResponse.of(HttpStatus.OK);
        }
    });

    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();

As described in the example, ``service()`` and ``serviceUnder()`` perform an exact match and a prefix match
on a request path respectively. `ServerBuilder`_ also provides advanced path mapping such as regex and glob
pattern matching.

Also, we decorated the second service using LoggingService_, which logs all requests and responses. You might
be interested in decorating a service using other decorators, for example to gather metrics.

You can also use an arbitrary object that's annotated by the ``@Path`` annotation using ``annotatedService()``.


SSL/TLS
-------

You can also add an HTTPS port with your certificate and its private key files:

.. code-block:: java

    import static com.linecorp.armeria.common.SessionProtocol.HTTPS;

    ServerBuilder sb = new ServerBuilder();
    sb.port(8443, HTTPS)
      .sslContext(HTTPS, new File("certificate.crt"), new File("private.key"), "myPassphrase");
    ...

Virtual hosts
-------------

Use ``ServerBuilder.withVirtualHost()`` to configure `a name-based virtual host`_:

.. code-block:: java

    import com.linecorp.armeria.server.VirtualHost;
    import com.linecorp.armeria.server.VirtualHostBuilder;

    ServerBuilder sb = new ServerBuilder();
    // Configure foo.com.
    sb.withVirtualHost("foo.com")
      .service(...)
      .sslContext(...)
      .and() // Configure *.bar.com.
      .withVirtualHost("*.bar.com")
      .service(...)
      .sslContext(...)
      .and() // Configure the default virtual host.
      .service(...)
      .sslContext(...);
    ...

See also
--------

- :ref:`server-decorator`
