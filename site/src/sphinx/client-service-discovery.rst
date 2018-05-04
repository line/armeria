.. _client-service-discovery:

Client-side load balancing and service discovery
================================================

You can configure an Armeria client to distribute its requests to more than one server autonomously, unlike
traditional server-side load balancing where the requests go through a dedicated load balancer such as
`L4 and L7 switches <https://en.wikipedia.org/wiki/Multilayer_switch#Layer_4%E2%80%937_switch,_web_switch,_or_content_switch>`_.

There are 4 elements involved in client-side load balancing in Armeria:

- :api:`Endpoint` represents an individual host (with an optional port number) and its weight.
- :api:`EndpointGroup` represents a set of :apiplural:`Endpoint`.
- :api:`EndpointGroupRegistry` is a global registry of :apiplural:`EndpointGroup` where each
  :api:`EndpointGroup` is identified by its unique name.
- A user specifies the target group name in the authority part of a URI,
  e.g. ``http://group:my_group/`` where ``my_group`` is the group name, prefixed with ``group:``.

.. uml::

    @startuml
    hide empty members

    class EndpointGroupRegistry <<singleton>> {
        groups : Map<String, EndpointGroup>
    }

    class EndpointGroup {
        endpoints : Set<Endpoint>
    }

    class Endpoint {
        host : String
        port : int
        weight : int
    }

    EndpointGroupRegistry o-right- "*" EndpointGroup
    EndpointGroup o-right- "*" Endpoint
    @enduml

.. _creating-endpoint-group:


Creating an ``EndpointGroup``
-----------------------------
There are various :api:`EndpointGroup` implementations provided out of the box, but let's start simple with
:api:`StaticEndpointGroup` which always yields a pre-defined set of :apiplural:`Endpoint` specified
at construction time:

.. code-block:: java

    // Create a group of well-known search engine endpoints.
    EndpointGroup searchEngineGroup = new StaticEndpointGroup(
            Endpoint.of("www.google.com", 443),
            Endpoint.of("www.bing.com", 443),
            Endpoint.of("duckduckgo.com", 443);

    List<Endpoint> endpoints = searchEngineGroup.endpoints();
    assert endpoints.contains(Endpoint.of("www.google.com", 443));
    assert endpoints.contains(Endpoint.of("www.bing.com", 443));
    assert endpoints.contains(Endpoint.of("duckduckgo.com", 443));


Registering an ``EndointGroup``
-------------------------------
An :api:`EndpointGroup` becomes visible by a client such as :api:`HttpClient` only after it's registered in
:api:`EndpointGroupRegistry`. You need to specify 2 more elements to register an :api:`EndpointGroup`:

- The name of the :api:`EndpointGroup`
- An :api:`EndpointSelectionStrategy` that determines which :api:`Endpoint` is used for each request

  - Use ``EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN`` for weighted round robin.
  - Use ``EndpointSelectionStrategy.ROUND_ROBIN`` for unweighted round robin.
  - Use :api:`StickyEndpointSelectionStrategy` if you want to pin the requests based on a criteria
    such as a request parameter value.
  - You can implement your own :api:`EndpointSelectionStrategy`.

The following example registers the ``searchEngineGroup`` we created at :ref:`creating-endpoint-group`:

.. code-block:: java

    EndpointGroupRegistry.register("search_engines", searchEngineGroup,
                                   EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

    assert EndpointGroupRegistry.get("search_engines") == searchEngineGroup;

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

Once an :api:`EndpointGroup` is registered, you can use its name in the authority part of a URI:

.. code-block:: java

    // Create an HTTP client that sends requests to the 'search_engines' group.
    HttpClient client = HttpClient.of("https://group:search_engines/");

    // Send a GET request to each search engine.
    List<CompletableFuture<?>> futures = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
        final HttpResponse res = client.get("/");
        final CompletableFuture<AggregatedHttpMessage> f = res.aggregate();
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

    // Unregister the group from the registry.
    EndpointGroupRegistry.unregister("search_engines");
    // Release all resources claimed by the group.
    searchEngines.close();

``close()`` is a no-op for some :api:`EndpointGroup` implementations, but not all implementations are so,
especially those which updates the :api:`Endpoint` list dynamically, such as refreshing the list periodically.

.. note::

    An :api:`EndpointGroup`, whose :apiplural:`Endpoint` change even after it's instantiated and registered,
    is called *dynamic endpoint group*.


Removing unhealthy ``Endpoint`` with ``HttpHealthCheckedEndpointGroup``
-----------------------------------------------------------------------
:api:`HttpHealthCheckedEndpointGroup` decorates an existing :api:`EndpointGroup` to filter out the unhealthy
:apiplural:`Endpoint` from it so that a client has less chance of sending its requests to the unhealthy
:apiplural:`Endpoint`. It determines the healthiness by sending so called 'health check request' to each
:api:`Endpoint`, which is by default a simple ``GET`` request to a certain path. If an :api:`Endpoint`
responds with non-200 status code or does not respond in time, it will be marked as unhealthy and thus
be removed from the list.

.. code-block:: java

    // Create an EndpointGroup with 2 Endpoints.
    StaticEndpointGroup group = new StaticEndpointGroup(
        Endpoint.of("192.168.0.1", 80),
        Endpoint.of("192.168.0.2", 80));

    // Decorate the EndpointGroup with HttpHealthCheckedEndpointGroup
    // that sends HTTP health check requests to '/internal/l7check' every 10 seconds.
    HttpHealthCheckedEndpointGroup healthCheckedGroup =
            new HttpHealthCheckedEndpointGroupBuilder(group, "/internal/l7check")
                    .protocol(SessionProtocol.HTTP)
                    .retryInterval(Duration.ofSeconds(10))
                    .build();

    // Wait until the initial health check is finished.
    healthCheckedGroup.awaitInitialEndpoints();

    // Register the health-checked group.
    EndpointGroupRegistry.register("my-group", healthCheckedGroup);

.. note::

    You can decorate *any* :api:`EndpointGroup` implementations with :api:`HttpHealthCheckedEndpointGroup`,
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
            new DnsAddressEndpointGroupBuilder("www.google.com")
                    // Refresh more often than every 10 seconds and
                    // less often than every 60 seconds even if DNS server asks otherwise.
                    .ttl(/* minTtl */ 10, /* maxTtl */ 60)
                    .build();

    // Wait until the initial DNS queries are finished.
    group.awaitInitialEndpoints();

:api:`DnsServiceEndpointGroup` is useful when accessing an internal service with
`SRV records <https://en.wikipedia.org/wiki/SRV_record>`_, which is often found in modern container
environments that leverage DNS for service discovery such as Kubernetes:

.. code-block:: java

    DnsServiceEndpointGroup group =
            new DnsServiceEndpointGroupBuilder("_http._tcp.example.com")
                    // Custom backoff strategy.
                    .backoff(Backoff.exponential(1000, 16000).withJitter(0.3))
                    .build();

    // Wait until the initial DNS queries are finished.
    group.awaitInitialEndpoints();

:api:`DnsTextEndpointGroup` is useful if you need to represent your :apiplural:`Endpoint` in a non-standard
form:

.. code-block:: java

    // A mapping function must be specified.
    DnsTextEndpointGroup group = DnsTextEndpointGroup.of("example.com", (byte[] text) -> {
        Endpoint e = /* Convert 'text' into an Endpoint here. */;
        return e
    });

    // Wait until the initial DNS queries are finished.
    group.awaitInitialEndpoints();


ZooKeeper-based service discovery with ``ZooKeeperEndpointGroup``
-----------------------------------------------------------------
See :ref:`advanced-zookeeper`.
