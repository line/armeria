/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import java.util.concurrent.TimeUnit;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.ArmeriaSettings;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;

final class DropwizardSupport {

    static boolean addExposition(ArmeriaSettings settings, ServerBuilder server,
                                 MeterRegistry meterRegistry) {
        final String metricsPath = settings.getMetricsPath();
        assert metricsPath != null;

        if (!(meterRegistry instanceof DropwizardMeterRegistry)) {
            return false;
        }
        final MetricRegistry dropwizardRegistry =
                ((DropwizardMeterRegistry) meterRegistry).getDropwizardRegistry();
        final ObjectMapper objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true));

        server.route()
              .get(settings.getMetricsPath())
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                   objectMapper.writeValueAsBytes(dropwizardRegistry)));
        return true;
    }

    private DropwizardSupport() {}
}
