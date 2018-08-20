.. _`a name-based virtual host`: https://en.wikipedia.org/wiki/Virtual_hosting#Name-based

.. _server-basics:

Server basics
=============

To start a server, you need to build it first. Use :api:`ServerBuilder`:

.. code-block:: java

    import com.linecorp.armeria.server.Server;
    import com.linecorp.armeria.server.ServerBuilder;

    ServerBuilder sb = new ServerBuilder();
    // TODO: Configure your server here.
    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    // Wait until the server is ready.
    future.join();

Ports
-----

To serve anything, you need to specify which TCP/IP port you want to bind onto:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    // Configure an HTTP port.
    sb.http(8080);
    // TODO: Add your services here.
    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();

Services
--------

Even if we opened a port, it's of no use if we didn't bind any services to them. Let's add some:

.. code-block:: java

    import com.linecorp.armeria.common.HttpParameters;
    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.HttpStatus;
    import com.linecorp.armeria.common.MediaType;

    import com.linecorp.armeria.server.AbstractHttpService;
    import com.linecorp.armeria.server.Server;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.annotation.Consumes;
    import com.linecorp.armeria.server.annotation.Default;
    import com.linecorp.armeria.server.annotation.Get;
    import com.linecorp.armeria.server.annotation.Param;
    import com.linecorp.armeria.server.annotation.Post;
    import com.linecorp.armeria.server.annotation.Produces;
    import com.linecorp.armeria.server.logging.LoggingService;

    ServerBuilder sb = new ServerBuilder();
    sb.http(8080);

    // Add a simple 'Hello, world!' service.
    sb.service("/", (ctx, res) -> HttpResponse.of("Hello, world!"));

    // Using path variables:
    sb.service("/greet/{name}", new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            String name = ctx.pathParam("name");
            return HttpResponse.of("Hello, %s!", name);
        }
    }.decorate(LoggingService.newDecorator())); // Enable logging

    // Using an annotated service object:
    sb.annotatedService(new Object() {
        @Get("/greet2/:name") // `:name` style is also available
        public HttpResponse greet(@Param("name") String name) {
            return HttpResponse.of("Hello, %s!", name);
        }
    });

    // Just in case your name contains a slash:
    sb.serviceUnder("/greet3", (ctx, req) -> {
        String path = ctx.mappedPath();  // Get the path without the prefix ('/greet3')
        String name = path.substring(1); // Strip the leading slash ('/')
        return HttpResponse.of("Hello, %s!", name);
    });

    // Using an annotated service object:
    sb.annotatedService(new Object() {
        @Get("regex:^/greet4/(?<name>.*)$")
        public HttpResponse greet(@Param("name") String name) {
            return HttpResponse.of("Hello, %s!", name);
        }
    });

    // Using a query parameter (e.g. /greet5?name=alice) on an annotated service object:
    sb.annotatedService(new Object() {
        @Get("/greet5")
        public HttpResponse greet(@Param("name") String name,
                                  @Param("title") @Default("Mr.") String title) {
            // "Mr." is used by default if there is no title parameter in the request.
            return HttpResponse.of("Hello, %s %s!", title, name);
        }
    });

    // Getting a map of query parameters on an annotated service object:
    sb.annotatedService(new Object() {
        @Get("/greet6")
        public HttpResponse greet(HttpParameters parameters) {
            return HttpResponse.of("Hello, %s!", parameters.get("name"));
        }
    });

    // Using media type negotiation:
    sb.annotatedService(new Object() {
        @Get("/greet7")
        @Produces("application/json;charset=UTF-8")
        public HttpResponse greetGet(@Param("name") String name) {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"name\":\"%s\"}", name);
        }

        @Post("/greet7")
        @Consumes("application/x-www-form-urlencoded")
        public HttpResponse greetPost(@Param("name") String name) {
            return HttpResponse.of(HttpStatus.OK);
        }
    });

    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();

As described in the example, ``service()`` and ``serviceUnder()`` perform an exact match and a prefix match
on a request path respectively. :api:`ServerBuilder` also provides advanced path mapping such as regex and
glob pattern matching.

Also, we decorated the second service using :api:`LoggingService`, which logs all requests and responses.
You might be interested in decorating a service using other decorators, for example to gather metrics.

You can also use an arbitrary object that's annotated by the ``@Path`` annotation using ``annotatedService()``.


SSL/TLS
-------

You can also add an HTTPS port with your certificate and its private key files:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    sb.https(8443)
      .tls(new File("certificate.crt"), new File("private.key"), "myPassphrase");
    ...


PROXY protocol
--------------

Armeria supports both text (v1) and binary (v2) versions of `PROXY protocol <https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt>`_.
If your server is behind a load balancer such as `HAProxy <https://www.haproxy.org/>`_ and
`AWS ELB <https://aws.amazon.com/elasticloadbalancing/>`_, you could consider enabling the PROXY protocol:

.. code-block:: java

    import static com.linecorp.armeria.common.SessionProtocol.HTTP;
    import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
    import static com.linecorp.armeria.common.SessionProtocol.PROXY;

    ServerBuilder sb = new ServerBuilder();
    sb.port(8080, PROXY, HTTP);
    sb.port(8443, PROXY, HTTPS);
    ...


Serving HTTP and HTTPS on the same port
---------------------------------------

For whatever reason, you may have to serve both HTTP and HTTPS on the same port. Armeria is one of the few
implementations that supports port unification:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    sb.port(8888, HTTP, HTTPS);
    // Enable PROXY protocol, too.
    sb.port(9999, PROXY, HTTP, HTTPS);
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
      .tls(...)
      .and() // Configure *.bar.com.
      .withVirtualHost("*.bar.com")
      .service(...)
      .tls(...)
      .and() // Configure the default virtual host.
      .service(...)
      .tls(...);
    ...

See also
--------

- :ref:`server-decorator`
