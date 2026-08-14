package example.armeria.xds;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class RouteMetricsClient extends SimpleDecoratingHttpClient {

    private final MeterRegistry registry;

    RouteMetricsClient(HttpClient delegate, MeterRegistry registry) {
        super(delegate);
        this.registry = registry;
    }

    static Function<? super HttpClient, ? extends HttpClient> newDecorator(MeterRegistry registry) {
        return delegate -> new RouteMetricsClient(delegate, registry);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponse res = unwrap().execute(ctx, req);
        ctx.log().whenComplete().thenAccept(log -> {
            final String layer2Node = log.responseHeaders().get("layer2-node", "unknown");
            final String layer3Node = log.responseHeaders().get("layer3-node", "unknown");
            final String status = log.responseHeaders().status().codeAsText();
            Counter.builder("layer1.route")
                   .tag("layer2.node", layer2Node)
                   .tag("layer3.node", layer3Node)
                   .tag("http.status", status)
                   .register(registry)
                   .increment();
        });
        return res;
    }
}
