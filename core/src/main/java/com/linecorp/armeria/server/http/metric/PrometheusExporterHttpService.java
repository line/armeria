/**
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.metric;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.util.Objects;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

/**
 * Exports Prometheus metrics.
 */
public class PrometheusExporterHttpService extends AbstractHttpService {
    private static final MediaType CONTENT_TYPE_004 = MediaType.parse(TextFormat.CONTENT_TYPE_004);

    private final CollectorRegistry prometheusRegistry;

    /**
     * Create a {@link PrometheusExporterHttpService} instance.
     * @param prometheusRegistry Prometheus registry
     */
    public PrometheusExporterHttpService(CollectorRegistry prometheusRegistry) {
        Objects.requireNonNull(prometheusRegistry, "prometheusRegistry");
        this.prometheusRegistry = prometheusRegistry;
    }

    @Override
    protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                         HttpResponseWriter res) throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(stream)) {
            TextFormat.write004(writer, prometheusRegistry.metricFamilySamples());
        }
        res.respond(HttpStatus.OK, CONTENT_TYPE_004, stream.toByteArray());
    }

    @Override
    protected void doPost(ServiceRequestContext ctx, HttpRequest req,
                          HttpResponseWriter res) throws Exception {
        doGet(ctx, req, res);
    }
}
