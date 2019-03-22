package example.armeria.proxy;

import java.time.Duration;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsServiceEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HttpHealthCheckedEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HttpHealthCheckedEndpointGroupBuilder;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

public final class ProxyService extends AbstractHttpService {

    /**
     * We used hardcoded backend addresses. But you can use other service discovery mechanisms to configure
     * backends dynamically using {@link DnsServiceEndpointGroup}, ZooKeeper or Central Dogma.
     * See <a href="https://line.github.io/armeria/client-service-discovery.html">service discovery</a>,
     * <a href="https://line.github.io/armeria/advanced-zookeeper.html#advanced-zookeeper">Service discovery
     * with ZooKeeper</a> and <a href="https://line.github.io/centraldogma/">centraldogma</a>.
     */
    private static final EndpointGroup animationGroup = new StaticEndpointGroup(
            Endpoint.of("127.0.0.1", 8081),
            Endpoint.of("127.0.0.1", 8082),
            Endpoint.of("127.0.0.1", 8083));

    private final HttpClient loadBalancingClient;

    private final boolean filterHeaders;

    public ProxyService() throws InterruptedException {
        loadBalancingClient = newLoadBalancingClient();
        filterHeaders = false;
    }

    private static HttpClient newLoadBalancingClient() throws InterruptedException {
        // Send HTTP health check requests to '/internal/l7check' every 10 seconds.
        final HttpHealthCheckedEndpointGroup healthCheckedGroup =
                new HttpHealthCheckedEndpointGroupBuilder(animationGroup, "/internal/l7check")
                        .protocol(SessionProtocol.HTTP)
                        .retryInterval(Duration.ofSeconds(10))
                        .build();

        // Wait until the initial health check is finished.
        healthCheckedGroup.awaitInitialEndpoints();

        EndpointGroupRegistry.register("animation_apis", healthCheckedGroup,
                                       // You can use EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN or even
                                       // implement your own strategy to balance requests.
                                       EndpointSelectionStrategy.ROUND_ROBIN);

        return new HttpClientBuilder("http://group:animation_apis")
                // Disable timeout to serve infinite streaming response.
                .defaultResponseTimeoutMillis(0)
                .build();
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        // We can just send the request from a browser to a backend and the response from the backend
        // to the browser. You don't have to implement your own backpressure control because Armeria handles
        // it by nature. Let's take a look at how a response is sending to describe it.
        // This is the flow:
        //                         -------------------------------------------
        //               streaming |                            Proxy server |   streaming
        //   Browser <-------------|(socket1)     HttpResponse      (socket2)|<------------ (socket3) Backend
        //                         |             internal queue              |
        //                         |   <-  (((((((...data...((((((((() <-    |
        //                         |                                         |
        //                         -------------------------------------------
        //
        // A streaming data is read from the socket2 which is connected to the backend and stored into the
        // internal queue in the HttpResponse. Then, the first streaming data is written to the socket1 which is
        // connected to the browser. If it succeeds, the proxy server fetches one more data from the internal
        // queue and writes it to the socket1 again. So the proxy server pulls the data from the internal queue
        // only when it can write to the socket1.
        // That means if the brower cannot receive the data fast enough, which makes the socket1 full,
        // the proxy server does not forward data and just stores data in the queue in the HttpResponse.
        // Someone might think, at this point, that makes the internal queue full. That is not true. The proxy
        // server reads the data from the backend automatically until the amount of the data in the queue is
        // under certain threshold. If the amount passes the threshold, the proxy server stops reading from
        // socket2. So the socket3 is full as well and the backend can pause to produce streaming data.
        // This applies to the request in the same way.
        final HttpResponse res = loadBalancingClient.execute(req);
        if (filterHeaders) {
            // You can remove or add specific headers to a response.
            return filteredResponse(res);
        }

        return res;
    }

    private static HttpResponse filteredResponse(HttpResponse res) {
        return new FilteredHttpResponse(res) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                if (obj instanceof HttpHeaders) {
                    final HttpHeaders resHeaders = ((HttpHeaders) obj).toMutable();
                    // Remove a header.
                    resHeaders.remove(HttpHeaderNames.of("my-sensitive-header"));
                    return resHeaders;
                }
                return obj;
            }
        };
    }
}
