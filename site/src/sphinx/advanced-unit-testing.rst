.. _advanced-unit-testing:

Unit-testing ``Client`` and ``Service``
=======================================

A unit test of a client or a service will require you to prepare two objects:

- :api:`ClientRequestContext` or :api:`ServiceRequestContext`
- :api:`HttpRequest` or :api:`RpcRequest`

:api:`ClientRequestContext` or :api:`ServiceRequestContext` is a more complex object with many properties than
:api:`HttpRequest` or :api:`RpcRequest`, and thus Armeria provides the API dedicated to building a fake context
object easily:

.. code-block:: java

    import org.junit.Before;
    import org.junit.Test;

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.AggregatedHttpResponse;
    import com.linecorp.armeria.client.ClientRequestContext;
    import com.linecorp.armeria.server.ServiceRequestContext;

    public class MyJUnit4Test {

        private MyClient client;
        private MyService service;

        @Before
        public void setUp() {
            client = ...;
            service = ...;
        }

        @Test
        public void testClient() throws Exception {
            // Given
            HttpRequest req = HttpRequest.of(HttpMethod.GET, "/greet?name=foo");
            ClientRequestContext cctx = ClientRequestContext.of(req);

            // When
            HttpResponse res = client.execute(cctx, req);

            // Then
            AggregatedHttpResponse aggregatedRes = res.aggregate().get();
            assertEquals(200, aggregatedRes.status().code());
        }

        @Test
        public void testService() throws Exception {
            // Given
            HttpRequest req = HttpRequest.of(HttpMethod.POST, "/greet",
                                             MediaType.JSON_UTF_8,
                                             "{ \"name\": \"foo\" }");
            ServiceRequestContext sctx = ServiceRequestContext.of(req);

            // When
            HttpResponse res = service.serve(sctx, req);

            // Then
            AggregatedHttpResponse aggregatedRes = res.aggregate().get();
            assertEquals(200, aggregatedRes.status().code());
        }
    }

Although the fake context returned by ``ClientRequestContext.of()`` and ``ServiceRequestContext.of()`` will
provide sensible defaults, you can override its default properties using a builder:

.. code-block:: java

    import java.net.InetAddress;
    import java.net.InetSocketAddress;
    import java.util.Map;

    import com.linecorp.armeria.common.SessionProtocol;
    import com.linecorp.armeria.client.ClientRequestContext;
    import com.linecorp.armeria.server.RoutingResult;
    import com.linecorp.armeria.server.ServiceRequestContext;

    HttpRequest req = HttpRequest.of(...);

    ClientRequestContext cctx =
            ClientRequestContext.builder(req)
                                .sessionProtocol(SessionProtocol.H1C)
                                .remoteAddress(new InetSocketAddress("192.168.0.2", 443))
                                .build();

    RoutingResult routingResult =
            RoutingResult.builder()
                         .path("/mapped/path")                       // Mapped path
                         .query("foo=bar&baz=qux")                   // Query string
                         .pathParams(Map.of("pathParam1", "value1",  // Path parameters
                                            "pathParam2", "value2"))
                         .build();

    ServiceRequestContext sctx =
            ServiceRequestContext.builder(req)
                                 .clientAddress(InetAddress.getByName("192.168.1.2"))
                                 .routingResult(routingResult);
                                 .build();

Using a fake context to emulate an incoming request
---------------------------------------------------

It is usually not necessary to build a context object by yourself except when writing a unit test,
because Armeria will always create a context object for you. However, you may need to build a fake context and
invoke your request processing pipeline with it when you want to handle the requests received via other sources
such as:

- Non-Armeria services
- Non-HTTP protocols, e.g. Kafka and STOMP
- Timers, i.e. Trigger a certain request every N minutes.

The following example shows how to emit a fake request every minute:

.. code-block:: java

    import java.util.concurrent.ScheduledExecutorService;
    import java.util.concurrent.TimeUnit;

    import com.linecorp.armeria.server.HttpService;

    ScheduledExecutorService executor = ...;
    HttpService sessionManagementService = (ctx, req) -> ...;

    // Send a session expiration request to the session management service
    // every minute.
    executor.scheduleWithFixedDelay(() -> {
        HttpRequest req = HttpRequest.of(HttpMethod.POST, "/expire_stall_sessions");
        ServiceRequestContext ctx = ServiceRequestContext.of(req);
        try {
            HttpResponse res = sessionManagementService.servce(ctx, req);
            AggregatedHttpResponse aggregatedRes = res.aggregate().get();
            if (aggregatedRes.status().code() != 200) {
                System.err.println("Failed to expire stall sessions: " +
                                   aggregatedRes);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }, 1, 1, TimeUnit.MINUTES);
