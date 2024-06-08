/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.metric;

import static java.util.Objects.requireNonNull;

import java.io.OutputStreamWriter;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

/**
 * Exposes Prometheus metrics in <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">text
 * format 0.0.4 or OpenMetrics format</a>.
 *
 * @deprecated Use {@code PrometheusExpositionService} in {@code armeria-prometheus1} module instead.
 */
@Deprecated
public final class PrometheusExpositionService extends AbstractHttpService implements TransientHttpService {

    /**
     * Returns a new {@link PrometheusExpositionService} that exposes Prometheus metrics from
     * {@link CollectorRegistry#defaultRegistry}.
     */
    public static PrometheusExpositionService of() {
        return of(CollectorRegistry.defaultRegistry);
    }

    /**
     * Returns a new {@link PrometheusExpositionService} that exposes Prometheus metrics from the specified
     * {@link CollectorRegistry}.
     */
    public static PrometheusExpositionService of(CollectorRegistry collectorRegistry) {
        return new PrometheusExpositionService(collectorRegistry, Flags.transientServiceOptions());
    }

    /**
     * Returns a new {@link PrometheusExpositionServiceBuilder} created with
     * {@link CollectorRegistry#defaultRegistry}.
     */
    public static PrometheusExpositionServiceBuilder builder() {
        return builder(CollectorRegistry.defaultRegistry);
    }

    /**
     * Returns a new {@link PrometheusExpositionServiceBuilder} created with the specified
     * {@link CollectorRegistry}.
     */
    public static PrometheusExpositionServiceBuilder builder(CollectorRegistry collectorRegistry) {
        return new PrometheusExpositionServiceBuilder(collectorRegistry);
    }

    private final CollectorRegistry collectorRegistry;
    private final Set<TransientServiceOption> transientServiceOptions;

    /**
     * Creates a new instance.
     *
     * @param collectorRegistry Prometheus registry
     *
     * @deprecated Use {@link #of(CollectorRegistry)}.
     */
    @Deprecated
    public PrometheusExpositionService(CollectorRegistry collectorRegistry) {
        this(collectorRegistry, Flags.transientServiceOptions());
    }

    PrometheusExpositionService(CollectorRegistry collectorRegistry,
                                Set<TransientServiceOption> transientServiceOptions) {
        this.collectorRegistry = requireNonNull(collectorRegistry, "collectorRegistry");
        this.transientServiceOptions =
                ImmutableSet.copyOf(requireNonNull(transientServiceOptions, "transientServiceOptions"));
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final String accept = req.headers().get(HttpHeaderNames.ACCEPT);
        final String format = TextFormat.chooseContentType(accept);
        final ByteBuf buffer = ctx.alloc().buffer();
        boolean success = false;
        try (ByteBufOutputStream byteBufOutputStream = new ByteBufOutputStream(buffer);
             OutputStreamWriter writer = new OutputStreamWriter(byteBufOutputStream)) {
            TextFormat.writeFormat(format, writer, collectorRegistry.metricFamilySamples());
            success = true;
        } finally {
            if (!success) {
                buffer.release();
            }
        }
        return HttpResponse.of(HttpStatus.OK, MediaType.parse(format), HttpData.wrap(buffer));
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doGet(ctx, req);
    }

    @Override
    public Set<TransientServiceOption> transientServiceOptions() {
        return transientServiceOptions;
    }
}
