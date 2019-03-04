.. _client-timeouts:

Overriding client timeouts
==========================

Sometimes, the default timeouts used by the Armeria clients does not suit a particular scenario well.
In these cases, you might want to override the timeout settings.

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

Using JVM system properties
---------------------------

You can override the default client timeouts by specifying the following JVM system properties if you do not
prefer setting it programmatically:

- ``com.linecorp.armeria.defaultResponseTimeoutMillis``

  - the default client-side timeout of a response in milliseconds.

- ``com.linecorp.armeria.defaultClientIdleTimeoutMillis``

  - the default client-side idle timeout of a connection for keep-alive in milliseconds.

Note that these properties have effect only when you did not specify these properties programmatically.

.. note::

    See :api:`Flags` for the complete list of JVM system properties in Armeria.

Adjusting connection idle timeout programmatically
--------------------------------------------------

You need to build a non-default :api:`ClientFactory` using :api:`ClientFactoryBuilder` to override the default
client connection idle timeout of 10 seconds programmatically:

.. code-block:: java

    import com.linecorp.armeria.client.ClientFactory;
    import com.linecorp.armeria.client.ClientFactoryBuilder;

    ClientFactory factory = new ClientFactoryBuilder()
            // Shorten the idle connection timeout from 10s to 5s.
            .idleTimeout(Duration.ofSeconds(5))
            // Enable HTTP/1 request pipelining.
            .useHttp1Pipelining(true)
            .build();

Note that :api:`ClientFactory` implements ``java.lang.AutoCloseable`` and thus needs to be closed when you
terminate your application. On ``close()``, :api:`ClientFactory` will close all the connections it manages
and abort any requests in progress.
