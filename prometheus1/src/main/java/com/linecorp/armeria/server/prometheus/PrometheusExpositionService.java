/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.server.prometheus;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.prometheus.metrics.expositionformats.ExpositionFormatWriter;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Exposes Prometheus metrics in <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">
 * EXPOSITION FORMATS</a>.
 */
@UnstableApi
public final class PrometheusExpositionService extends AbstractHttpService
        implements TransientHttpService {

    /**
     * Returns a new {@link PrometheusExpositionService} that exposes Prometheus metrics from
     * {@link PrometheusRegistry#defaultRegistry}.
     */
    public static PrometheusExpositionService of() {
        return of(PrometheusRegistry.defaultRegistry);
    }

    /**
     * Returns a new {@link PrometheusExpositionService} that exposes Prometheus metrics from
     * the specified {@link PrometheusRegistry}.
     */
    public static PrometheusExpositionService of(PrometheusRegistry prometheusRegistry) {
        return builder(prometheusRegistry).build();
    }

    /**
     * Returns a new {@link PrometheusExpositionServiceBuilder} created with
     * {@link PrometheusRegistry#defaultRegistry}.
     */
    public static PrometheusExpositionServiceBuilder builder() {
        return builder(PrometheusRegistry.defaultRegistry);
    }

    /**
     * Returns a new {@link PrometheusExpositionServiceBuilder} created with the specified
     * {@link PrometheusRegistry}.
     */
    public static PrometheusExpositionServiceBuilder builder(PrometheusRegistry prometheusRegistry) {
        return new PrometheusExpositionServiceBuilder(
                requireNonNull(prometheusRegistry, "prometheusRegistry"));
    }

    private final PrometheusRegistry prometheusRegistry;
    private final ExpositionFormats expositionFormats = ExpositionFormats.init();
    private final Set<TransientServiceOption> transientServiceOptions;

    PrometheusExpositionService(PrometheusRegistry prometheusRegistry,
                                Set<TransientServiceOption> transientServiceOptions) {
        this.prometheusRegistry = prometheusRegistry;
        this.transientServiceOptions = ImmutableSet.copyOf(transientServiceOptions);
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final String accept = req.headers().get(HttpHeaderNames.ACCEPT);
        final ByteBuf buffer = ctx.alloc().buffer();
        final String format;
        boolean success = false;
        try (ByteBufOutputStream byteBufOutputStream = new ByteBufOutputStream(buffer)) {
            final ExpositionFormatWriter writer = expositionFormats.findWriter(accept);
            format = writer.getContentType();
            writer.write(byteBufOutputStream, prometheusRegistry.scrape());
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
