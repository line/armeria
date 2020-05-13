package example.armeria.proxy;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.dns.DnsServiceEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

public final class ProxyService extends AbstractHttpService {

    // This is a simplified example. Please refer to
    // https://tools.ietf.org/html/rfc7230#section-5.7.1 for more information about Via header.
    private static final String viaHeaderValue = "HTTP/2.0 Armeria proxy"; // The pseudonym is Armeria proxy.

    /**
     * We used hardcoded backend addresses. But you can use other service discovery mechanisms to configure
     * backends dynamically using {@link DnsServiceEndpointGroup}, ZooKeeper or Central Dogma.
     * See <a href="https://line.github.io/armeria/docs/client-service-discovery">service discovery</a>,
     * <a href="https://line.github.io/armeria/docs/advanced-zookeeper">Service discovery
     * with ZooKeeper</a> and <a href="https://line.github.io/centraldogma/">centraldogma</a>.
     */
    private static final EndpointGroup animationGroup = EndpointGroup.of(
            // You can use EndpointSelectionStrategy.weightedRoundRobin() or even
            // implement your own strategy to balance requests.
            EndpointSelectionStrategy.roundRobin(),
            Endpoint.of("127.0.0.1", 8081),
            Endpoint.of("127.0.0.1", 8082),
            Endpoint.of("127.0.0.1", 8083));

    private final WebClient loadBalancingClient;

    private final boolean addForwardedToRequestHeaders;
    private final boolean addViaToResponseHeaders;

    public ProxyService() throws ExecutionException, InterruptedException {
        loadBalancingClient = newLoadBalancingClient();
        addForwardedToRequestHeaders = true;
        addViaToResponseHeaders = true;
    }

    private static WebClient newLoadBalancingClient() throws ExecutionException, InterruptedException {
        // Send HTTP health check requests to '/internal/l7check' every 10 seconds.
        final HealthCheckedEndpointGroup healthCheckedGroup =
                HealthCheckedEndpointGroup.builder(animationGroup, "/internal/l7check")
                                          .protocol(SessionProtocol.HTTP)
                                          .retryInterval(Duration.ofSeconds(10))
                                          .build();

        // Wait until the initial health check is finished.
        healthCheckedGroup.whenReady().get();

        return WebClient.builder(SessionProtocol.HTTP, healthCheckedGroup)
                        // Disable timeout to serve infinite streaming response.
                        .responseTimeoutMillis(0)
                        .decorator(LoggingClient.newDecorator())
                        .build();
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (addForwardedToRequestHeaders) {
            addForwarded(ctx, req);
        }

        // We can just send the request from a browser to a backend and the response from the backend
        // to the browser. You don't have to implement your own backpressure control because Armeria handles
        // it by nature. Let's take a look at how a response is transferring from the backend to the browser:
        //
        //                         +-----------------------------------------+
        //               streaming |                            Proxy server |   streaming
        //   Browser <-------------|(socket1)     HttpResponse      (socket2)|<------------ (socket3) Backend
        //                         |             internal queue              |
        //                         |   <-  (((((((...data...((((((((() <-    |
        //                         |                                         |
        //                         +-----------------------------------------+
        //
        // 1. A streaming data is read from the socket2 which is connected to the backend.
        // 2. The data is stored into the internal queue in the HttpResponse.
        // 3. The first streaming data in the queue is written to the socket1 which is connected to the browser.
        // 4. If it succeeds, the proxy server fetches one more data from the internal queue and writes it to
        //    the socket1 again. (The proxy server does not pull the data from the internal queue
        //    until the previous write succeeds.)
        // 5. If the browser cannot receive the data fast enough, the send buffer of socket1 is going to be
        //    full due to the flow control of TCP (or HTTP/2 if you are using).
        // 6. The proxy server does not write data to socket1 but just stores data in the queue.
        // 7. If the amount of the data in the queue exceeds a certain threshold, the proxy server pauses to
        //    read data automatically from socket2. (This prevents the queue growing infinitely.)
        // 8. If the amount of the data in the queue reduces by sending data to the browser again, the proxy
        //    server resumes reading data automatically from socket2.
        // 9. If the receive buffer of socket2 is full, then send buffer of socket3 is going to be full as well.
        //    So the backend can pause to produce streaming data.
        //
        // This applies to the request in the same way.
        final HttpResponse res = loadBalancingClient.execute(req);
        if (addViaToResponseHeaders) {
            return addViaHeader(res);
        }
        return res;
    }

    private static HttpRequest addForwarded(ServiceRequestContext ctx, HttpRequest req) {
        // This is a simplified example. Please refer to https://tools.ietf.org/html/rfc7239
        // for more information about Forwarded header.
        final StringBuilder sb = new StringBuilder();
        sb.append("for: ").append(ctx.<InetSocketAddress>remoteAddress().getAddress().getHostAddress());
        sb.append(", host: ").append(req.authority());
        sb.append(", proto: ").append(ctx.sessionProtocol());

        return req.withHeaders(req.headers().toBuilder()
                                  .add(HttpHeaderNames.FORWARDED, sb.toString()));
    }

    private static HttpResponse addViaHeader(HttpResponse res) {
        return new FilteredHttpResponse(res) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                // You can remove or add specific headers to a response.
                if (obj instanceof ResponseHeaders) {
                    return ((ResponseHeaders) obj).toBuilder()
                                                  .add(HttpHeaderNames.VIA, viaHeaderValue)
                                                  .build();
                }
                return obj;
            }
        };
    }
}
