Sending custom HTTP headers with a Thrift call
==============================================
To send a custom HTTP header such as authentication token with a Thrift call, you can:

- use the ``Clients.withHttpHeaders()`` method or
- use the ``ClientOption.HTTP_HEADERS`` option.

Using ``Clients.withHttpHeaders()``
-----------------------------------

.. code-block:: java

    import static com.linecorp.armeria.common.http.HttpHeaderNames.AUTHORIZATION;
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

    import static com.linecorp.armeria.common.http.HttpHeaderNames.USER_AGENT;

    try (SafeClosedble ignored1 = Clients.withHttpHeader(USER_AGENT, myUserAgent)) {
        for (String cred : credentials) {
            try (SafeCloseable ignored2 = Clients.withHttpHeaders(AUTHORIZATION, cred)) {
                client.hello("authorized personnel");
            }
        }
    }

Using ``ClientOption.HTTP_HEADERS``
-----------------------------------
If you have a custom HTTP header whose value does not change often, you can use ``ClientOption.HTTP_HEADERS``:

.. code-block:: java

    import static com.linecorp.armeria.common.http.HttpHeaderNames.AUTHORIZATION;
    import com.linecorp.armeria.common.http.HttpHeaders;
    import com.linecorp.armeria.client.ClientBuilder;
    import com.linecorp.armeria.client.ClientOption;

    ClientBuilder cb = new ClientBuilder("tbinary+http://example.com/hello");
    cb.setHttpHeader(AUTHORIZATION, credential);
    // or:
    // cb.option(ClientOption.HTTP_HEADERS, HttpHeaders.of(AUTHORIZATION, credential));
    HelloService.Iface client = cb.build(HelloService.Iface.class);
    client.hello("authorized personnel");

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
