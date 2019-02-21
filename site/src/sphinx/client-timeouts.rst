.. _client-timeouts:

Overriding client timeouts
==========================
Sometimes, the default timeouts used by the Armeria-generated client does not suit a particular scenario well. In these cases, you might want to override the timeout settings.

Using ``ClientOptionsBuilder``
------------------------------

.. code-block:: java

    import java.time.Duration;

    import com.linecorp.armeria.common.util.SafeCloseable
    import com.linecorp.armeria.client.ClientOptionsBuilder;
    import com.linecorp.armeria.client.Clients;

    // Defaults are defined in com.linecorp.armeria.common.Flags and
    // com.linecorp.armeria.client.ClientOptions
    int responseTimeout = 15;
    int writeTimeout = 1;

    HelloService.Iface client = Clients.newClient(
            "tbinary+http://example.com/hello",
            HelloService.Iface.class,
            new ClientOptionsBuilder()
                    .defaultResponseTimeout(Duration.ofSeconds(responseTimeout))
                    .defaultWriteTimeout(Duration.ofSeconds(writeTimeout))
                    .build()
    );

Using ``ClientBuilder``
-----------------------

.. code-block:: java

    import java.time.Duration;

    import com.linecorp.armeria.client.ClientBuilder;

    int responseTimeout = 15;
    int writeTimeout = 1;

    HelloService.Iface client = new ClientBuilder("tbinary+http://example.com/hello")
            .defaultResponseTimeout(Duration.ofSeconds(responseTimeout))
            .defaultWriteTimeout(Duration.ofSeconds(writeTimeout))
            .build(HelloService.Iface.class);
