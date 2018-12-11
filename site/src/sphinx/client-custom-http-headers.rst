.. _client-custom-http-headers:

Sending custom HTTP headers
===========================
When send an RPC request, it is sometimes required to send HTTP headers with it, such as authentication token.
There are four ways to customize the HTTP headers of your RPC request:

- Using the ``Clients.withHttpHeaders()`` method
- Using the ``ClientOption.HTTP_HEADERS`` option
- Using the ``ClientBuilder.decorator()`` method
- Using a derived client

Using ``Clients.withHttpHeaders()``
-----------------------------------

.. code-block:: java

    import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
    import com.linecorp.armeria.common.util.SafeCloseable
    import com.linecorp.armeria.client.Clients;

    HelloService.Iface client = Clients.newClient("tbinary+http://example.com/hello",
                                                   HelloService.Iface.class);
    try (SafeCloseable ignored = Clients.withHttpHeaders(
            headers -> headers.set(AUTHORIZATION, credential))) {
        client.hello("authorized personnel");
    }

If you are setting only a single header, you can use ``Clients.withHttpHeader()`` simply:

.. code-block:: java

    try (SafeCloseable ignored = Clients.withHttpHeader(AUTHORIZATION, credential)) {
        client.hello("authorized personnel");
    }

You can also nest ``withHttpHeader(s)``. The following example will send both ``user-agent`` header and
``authorization`` header when calling ``client.hello()``:

.. code-block:: java

    import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;

    try (SafeClosedble ignored1 = Clients.withHttpHeader(USER_AGENT, myUserAgent)) {
        for (String cred : credentials) {
            try (SafeCloseable ignored2 = Clients.withHttpHeaders(AUTHORIZATION, cred)) {
                client.hello("authorized personnel");
            }
        }
    }

Using ``ClientOption.HTTP_HEADERS``
-----------------------------------
If you have a custom HTTP header whose value does not change often, you can use ``ClientOption.HTTP_HEADERS``
which is more efficient:

.. code-block:: java

    import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
    import com.linecorp.armeria.common.HttpHeaders;
    import com.linecorp.armeria.client.ClientBuilder;
    import com.linecorp.armeria.client.ClientOption;

    ClientBuilder cb = new ClientBuilder("tbinary+http://example.com/hello");
    cb.setHttpHeader(AUTHORIZATION, credential);
    // or:
    // cb.option(ClientOption.HTTP_HEADERS, HttpHeaders.of(AUTHORIZATION, credential));
    HelloService.Iface client = cb.build(HelloService.Iface.class);
    client.hello("authorized personnel");

Using ``ClientBuilder.decorator()``
-----------------------------------
If you want more freedom on how you manipulate the request headers, use a decorator:

.. code-block:: java

    ClientBuilder cb = new ClientBuilder("tbinary+http://example.com/hello");

    // Add a decorator that inserts the custom header.
    cb.decorator((delegate, ctx, req) -> { // See DecoratingClientFunction.
        req.headers().set(AUTHORIZATION, credential);
        return delegate.execute(ctx, req);
    });

    HelloService.Iface client = cb.build(HelloService.Iface.class);
    client.hello("authorized personnel");

Note that this method is as efficient as the ``ClientOption.HTTP_HEADERS`` option. Choose whichever you prefer.

Using a derived client
----------------------
Although not as simple as using ``withHttpHeaders()``, you can create a derived client to add more custom
headers to an existing client:

.. code-block:: java

    import com.linecorp.armeria.client.ClientOptionsBuilder;

    HelloService.Iface client = ...;
    HelloService.Iface derivedClient = Clients.newDerivedClient(client, options -> {
        ClientOptionsBuilder builder = new ClientOptionsBuilder(options);
        builder.decorator(...);  // Add a decorator.
        builder.httpHeader(AUTHORIZATION, credential); // Add an HTTP header.
        return builder.build();
    });
