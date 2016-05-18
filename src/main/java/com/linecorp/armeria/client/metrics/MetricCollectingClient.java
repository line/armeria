package com.linecorp.armeria.client.metrics;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.metrics.DropwizardMetricConsumer;
import com.linecorp.armeria.common.metrics.MetricConsumer;
import com.linecorp.armeria.common.util.CompletionActions;

public final class MetricCollectingClient extends DecoratingClient {

    /**
     * A {@link Client} decorator that tracks request stats using the Dropwizard metrics library.
     * To use, simply prepare a {@link MetricRegistry} and add this decorator to a client.
     *
     * @param metricRegistry the {@link MetricRegistry} to store metrics into.
     * @param metricNamePrefix the prefix of the names of the metrics created by the returned decorator.
     *
     * <p>Example:
     * <pre>{@code
     * MetricRegistry metricRegistry = new MetricRegistry();
     * MyService.Iface client = new ClientBuilder(uri)
     *         .decorate(MetricCollectingClient.newDropwizardDecorator(
     *                 metricRegistry, MetricRegistry.name("clients", "myService")))
     *         .build(MyService.Iface.class);
     * }
     * </pre>
     * <p>It is generally recommended to define your own name for the service instead of using something like
     * the Java class to make sure otherwise safe changes like renames don't break metrics.
     */
    public static Function<Client, MetricCollectingClient> newDropwizardDecorator(
            MetricRegistry metricRegistry, String metricNamePrefix) {

        return client -> new MetricCollectingClient(
                client,
                new DropwizardMetricConsumer(metricRegistry, metricNamePrefix));
    }

    private final MetricConsumer consumer;

    public MetricCollectingClient(Client delegate, MetricConsumer consumer) {
        super(delegate);
        this.consumer = requireNonNull(consumer, "consumer");
    }

    @Override
    public Response execute(ClientRequestContext ctx, Request req) throws Exception {
        ctx.awaitRequestLog()
           .thenAccept(consumer::onRequest)
           .exceptionally(CompletionActions::log);

        ctx.awaitResponseLog()
           .thenAccept(consumer::onResponse)
           .exceptionally(CompletionActions::log);

        return delegate().execute(ctx, req);
    }
}
