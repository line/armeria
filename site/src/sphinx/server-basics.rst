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

    import static com.linecorp.armeria.common.http.HttpSessionProtocols.HTTP;

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

    import com.linecorp.armeria.common.MediaType;
    import com.linecorp.armeria.common.http.HttpRequest;
    import com.linecorp.armeria.common.http.HttpResponseWriter;
    import com.linecorp.armeria.common.http.HttpStatus;

    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.http.AbstractHttpService;

    ServerBuilder sb = new ServerBuilder();
    sb.port(8080, HTTP);

    // Add a simple 'Hello, world!' service.
    sb.serviceAt("/", new AbstractHttpService() {
        @Override
        protected void doGet(ServiceRequestContext ctx,
                             HttpRequest req, HttpResponseWriter res) {
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, world!");
        }
    });

    // Similar to the 'Hello, world!' service, but gets the name from the request path
    sb.serviceUnder("/hello", new AbstractHttpService() {
        @Override
        protected void doGet(ServiceRequestContext ctx,
                             HttpRequest req, HttpResponseWriter res) {
            String path = ctx.mappedPath();  // Get the path without the prefix ('/hello')
            String name = path.substring(1); // Strip the leading slash ('/')
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, " + name + '!');
        }
    }.decorate(LoggingService::new));

    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();

As described in the example, ``serviceAt()`` and ``serviceUnder()`` performs an exact match and a prefix match
on a request path respectively. `ServerBuilder`_ also provides advanced path mapping such as regex and glob
pattern matching.

Also, we decorated the second service using LoggingService_, which logs all requests and responses. You might
be interested in decorating a service using other decorators, for example to gather metrics.


SSL/TLS
-------

You can also add an HTTPS port with your certificate and its private key files:

.. code-block:: java

    import static com.linecorp.armeria.common.http.HttpSessionProtocols.HTTPS;

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
      .serviceAt(...)
      .sslContext(...)
      .and() // Configure *.bar.com.
      .withVirtualHost("*.bar.com")
      .serviceAt(...)
      .sslContext(...)
      .and() // Configure the default virtual host.
      .serviceAt(...)
      .sslContext(...);
    ...

See also
--------

- :ref:`server-decorator`
