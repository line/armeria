package com.linecorp.armeria.client.metrics;

import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.common.metrics.DropwizardMetricConsumer;
import com.linecorp.armeria.common.metrics.MetricConsumer;

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
    public static Function<Client, Client> newDropwizardDecorator(MetricRegistry metricRegistry,
                                                                  String metricNamePrefix) {

        return client -> new MetricCollectingClient(
                client,
                new DropwizardMetricConsumer(metricRegistry, metricNamePrefix));
    }


    public MetricCollectingClient(Client client, MetricConsumer metricConsumer) {
        super(client, codec -> new MetricCollectingClientCodec(codec, metricConsumer), Function.identity());
    }
}
