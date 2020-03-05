.. _client-service-discovery:

Client-side load balancing and service discovery
================================================

You can configure an Armeria client to distribute its requests to more than one server autonomously, unlike
traditional server-side load balancing where the requests go through a dedicated load balancer such as
`L4 and L7 switches <https://en.wikipedia.org/wiki/Multilayer_switch#Layer_4%E2%80%937_switch,_web_switch,_or_content_switch>`_.

There are 3 elements involved in client-side load balancing in Armeria:

- :api:`Endpoint` represents an individual host (with an optional port number) and its weight.
- :api:`EndpointGroup` represents a set of :apiplural:`Endpoint`.
- A user specifies an :api:`EndpointGroup` when building a client.

.. _creating-endpoint-group:

Creating an ``EndpointGroup``
-----------------------------
There are various :api:`EndpointGroup` implementations provided out of the box, but let's start simple with
:api:`EndpointGroup` which always yields a pre-defined set of :apiplural:`Endpoint` specified at construction
time:

.. code-block:: java

    import com.linecorp.armeria.client.Endpoint;
    import com.linecorp.armeria.client.endpoint.EndpointGroup;

    // Create a group of well-known search engine endpoints.
    EndpointGroup searchEngineGroup = EndpointGroup.of(
            Endpoint.of("www.google.com", 443),
            Endpoint.of("www.bing.com", 443),
            Endpoint.of("duckduckgo.com", 443);

    List<Endpoint> endpoints = searchEngineGroup.endpoints();
    assert endpoints.contains(Endpoint.of("www.google.com", 443));
    assert endpoints.contains(Endpoint.of("www.bing.com", 443));
    assert endpoints.contains(Endpoint.of("duckduckgo.com", 443));


Choosing an ``EndpointSelectionStrategy``
-----------------------------------------
An :api:`EndpointGroup` is created with ``EndpointSelectionStrategy.weightedRoundRobin()`` by default,
unless specified otherwise. Armeria currently provides the following :api:`EndpointSelectionStrategy`
implementations out-of-the-box:

- ``EndpointSelectionStrategy.weightedRoundRobin`` for weighted round robin.
- ``EndpointSelectionStrategy.roundRobin`` for round robin.
- :api:`StickyEndpointSelectionStrategy` for pinning requests based on a criteria
  such as a request parameter value.
- You can also implement your own :api:`EndpointSelectionStrategy`.

An :api:`EndpointSelectionStrategy` can usually be specified as an input parameter or via a builder method
when you build an :api:`EndpointGroup`:

.. code-block:: java

    import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
    import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;

    EndpointSelectionStrategy strategy = EndpointSelectionStrategy.roundRobin();

    EndpointGroup group1 = EndpointGroup.of(
            strategy,
            Endpoint.of("127.0.0.1", 8080),
            Endpoint.of("127.0.0.1", 8081));

    EndpointGroup group2 =
            DnsAddressEndpointGroup.builder("example.com")
                                   .selectionStrategy(strategy)
                                   .build();

.. note::

    You can create an :api:`Endpoint` with non-default weight using ``withWeight()`` method:

    .. code-block:: java

        // The default weight is 1000.
        Endpoint endpointWithDefaultWeight = Endpoint.of("foo.com", 8080);
        Endpoint endpointWithCustomWeight = endpointWithDefaultWeight.withWeight(1500);
        assert endpointWithDefaultWeight.weight() == 1000;
        assert endpointWithCustomWeight.weight() == 1500;


Connecting to an ``EndpointGroup``
----------------------------------

Once an :api:`EndpointGroup` is created, you can specify it when creating a new client:

.. code-block:: java

    import static com.linecorp.armeria.common.SessionProtocol.HTTPS;

    import com.linecorp.armeria.client.WebClient;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.AggregatedHttpResponse;

    // Create an HTTP client that sends requests to the searchEngineGroup.
    WebClient client = WebClient.of(SessionProtocol.HTTPS, searchEngineGroup);

    // Send a GET request to each search engine.
    List<CompletableFuture<?>> futures = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
        final HttpResponse res = client.get("/");
        final CompletableFuture<AggregatedHttpResponse> f = res.aggregate();
        futures.add(f.thenRun(() -> {
            // And print the response.
            System.err.println(f.getNow(null));
        }));
    }

    // Wait until all GET requests are finished.
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();


.. _cleaning-up-endpoint-group:

Cleaning up an ``EndpointGroup``
--------------------------------

:api:`EndpointGroup` extends ``java.lang.AutoCloseable``, which means you need to call the ``close()``
method once you are done using it, usually when your application terminates:

.. code-block:: java

    // Release all resources claimed by the group.
    searchEngines.close();

``close()`` is a no-op for some :api:`EndpointGroup` implementations, but not all implementations are so,
especially those which updates the :api:`Endpoint` list dynamically, such as refreshing the list periodically.

.. note::

    An :api:`EndpointGroup`, whose :apiplural:`Endpoint` change even after it's instantiated and registered,
    is called *dynamic endpoint group*.


Removing unhealthy ``Endpoint`` with ``HealthCheckedEndpointGroup``
-------------------------------------------------------------------
:api:`HealthCheckedEndpointGroup` decorates an existing :api:`EndpointGroup` to filter out the unhealthy
:apiplural:`Endpoint` from it so that a client has less chance of sending its requests to the unhealthy
:apiplural:`Endpoint`. It determines the healthiness by sending so called 'health check request' to each
:api:`Endpoint`, which is by default a simple ``HEAD`` request to a certain path. If an :api:`Endpoint`
responds with non-200 status code or does not respond in time, it will be marked as unhealthy and thus
be removed from the list.

.. code-block:: java

    import static com.linecorp.armeria.common.SessionProtocol.HTTP;

    import com.linecorp.armeria.client.WebClient;
    import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup

    // Create an EndpointGroup with 2 Endpoints.
    EndpointGroup originalGroup = EndpointGroup.of(
        Endpoint.of("192.168.0.1", 80),
        Endpoint.of("192.168.0.2", 80));

    // Decorate the EndpointGroup with HealthCheckedEndpointGroup
    // that sends HTTP health check requests to '/internal/l7check' every 10 seconds.
    HealthCheckedEndpointGroup healthCheckedGroup =
            HealthCheckedEndpointGroup.builder(originalGroup, "/internal/l7check")
                                      .protocol(SessionProtocol.HTTP)
                                      .retryInterval(Duration.ofSeconds(10))
                                      .build();

    // Wait until the initial health check is finished.
    healthCheckedGroup.whenReady().get();

    // Specify healthCheckedGroup, not the originalGroup.
    WebClient client = WebClient.builder(SessionProtocol.HTTP, healthCheckedGroup)
                                .build();

.. note::

   You must specify the wrapped ``healthCheckedGroup`` when building a :api:`WebClient`, otherwise health
   checking will not be enabled.

.. note::

    You can decorate *any* :api:`EndpointGroup` implementations with :api:`HealthCheckedEndpointGroup`,
    including what we will explain later in this page.

DNS-based service discovery with ``DnsEndpointGroup``
-----------------------------------------------------
Armeria provides 3 DNS-based :api:`EndpointGroup` implementations:

- :api:`DnsAddressEndpointGroup` that retrieves the :api:`Endpoint` list from ``A`` and ``AAAA`` records
- :api:`DnsServiceEndpointGroup` that retrieves the :api:`Endpoint` list from ``SRV`` records
- :api:`DnsTextEndpointGroup` that retrieves the :api:`Endpoint` list from ``TXT`` records

They refresh the :api:`Endpoint` list automatically, respecting TTL values, and retry when DNS queries fail.

:api:`DnsAddressEndpointGroup` is useful when accessing an external service with multiple public IP addresses:

.. code-block:: java

    DnsAddressEndpointGroup group =
            DnsAddressEndpointGroup.builder("www.google.com")
                                   // Refresh more often than every 10 seconds and
                                   // less often than every 60 seconds even if DNS server asks otherwise.
                                   .ttl(/* minTtl */ 10, /* maxTtl */ 60)
                                   .build();

    // Wait until the initial DNS queries are finished.
    group.whenReady().get();

:api:`DnsServiceEndpointGroup` is useful when accessing an internal service with
`SRV records <https://en.wikipedia.org/wiki/SRV_record>`_, which is often found in modern container
environments that leverage DNS for service discovery such as Kubernetes:

.. code-block:: java

    import com.linecorp.armeria.client.endpoint.dns.DnsServiceEndpointGroup;
    import com.linecorp.armeria.client.retry.Backoff;

    DnsServiceEndpointGroup group =
            DnsServiceEndpointGroup.builder("_http._tcp.example.com")
                                   // Custom backoff strategy.
                                   .backoff(Backoff.exponential(1000, 16000).withJitter(0.3))
                                   .build();

    // Wait until the initial DNS queries are finished.
    group.whenReady().get();

:api:`DnsTextEndpointGroup` is useful if you need to represent your :apiplural:`Endpoint` in a non-standard
form:

.. code-block:: java

    import com.linecorp.armeria.client.endpoint.dns.DnsTextEndpointGroup;

    // A mapping function must be specified.
    DnsTextEndpointGroup group = DnsTextEndpointGroup.of("example.com", (byte[] text) -> {
        Endpoint e = /* Convert 'text' into an Endpoint here. */;
        return e
    });

    // Wait until the initial DNS queries are finished.
    group.whenReady().get();


ZooKeeper-based service discovery with ``ZooKeeperEndpointGroup``
-----------------------------------------------------------------
See :ref:`advanced-zookeeper`.
