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
     * We used hardcoded backend address. But we can use service discovery mechanism to configure backends
     * dynamically using {@link DnsServiceEndpointGroup}, ZooKeeper or Central Dogma.
     *
     * See <a href="https://line.github.io/armeria/client-service-discovery.html">service discovery</a>,
     * <a href="https://line.github.io/armeria/advanced-zookeeper.html#advanced-zookeeper">Service discovery
     * with ZooKeeper</a> and <a href="https://line.github.io/centraldogma">Central Dogma</a>.
     */
    private static final EndpointGroup animationGroup = new StaticEndpointGroup(
            Endpoint.of("localhost", 8081),
            Endpoint.of("localhost", 8082),
            Endpoint.of("localhost", 8083));

    private final HttpClient loadBalancingClient;

    private final boolean filterHeaders;

    public ProxyService() throws InterruptedException {
        loadBalancingClient = initLbClient();
        filterHeaders = false;
    }

    private static HttpClient initLbClient() throws InterruptedException {
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
                // Increase timeout to serve long streaming response.
                .defaultResponseTimeout(Duration.ofHours(1))
                .build();
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
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
