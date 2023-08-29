/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Date;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ClientCertificateMetricsTest {

    @Test
    void shouldMeasureClientCertValidity() {
        // If the test runs at 11:59:59 PM, it could fail.
        await().untilAsserted(() -> {
            final Instant now = Instant.now();
            final Instant notAfter = now.plus(10, ChronoUnit.DAYS);
            final SelfSignedCertificate ssc = new SelfSignedCertificate("armeria.dev", Date.from(now),
                                                                        Date.from(notAfter));
            try {
                final MeterRegistry meterRegistry = new SimpleMeterRegistry();
                ClientFactory factory = ClientFactory.builder()
                                                     .tls(ssc.certificate(), ssc.privateKey())
                                                     .meterRegistry(meterRegistry)
                                                     .build();
                final Map<String, Double> metrics = MoreMeters.measureAll(meterRegistry);
                assertThat(metrics)
                        .containsEntry(
                                "armeria.client.tls.certificate.validity.days#value{common.name=armeria.dev}",
                                9.0)
                        .containsEntry("armeria.client.tls.certificate.validity#value{common.name=armeria.dev}",
                                       1.0);
                factory.close();
            } finally {
                ssc.delete();
            }
        });
    }
}
