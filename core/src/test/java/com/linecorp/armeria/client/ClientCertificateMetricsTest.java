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

import java.io.InputStream;
import java.security.cert.CertificateException;
import java.sql.Date;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Map;

import org.assertj.core.api.AbstractDoubleAssert;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeterBinders;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.armeria.server.ServerTlsCertificateMetricsTest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ClientCertificateMetricsTest {

    private static final String RESOURCE_PATH_PREFIX =
            "/testing/core/" + ServerTlsCertificateMetricsTest.class.getSimpleName() + '/';

    @Test
    void shouldMeasureClientCertValidityInClientFactory() {
        // If the test runs at 11:59:59 PM, it could fail.
        await().untilAsserted(() -> {
            final Instant now = Instant.now();
            final Instant notAfter = now.plus(10, ChronoUnit.DAYS);
            final SelfSignedCertificate ssc = new SelfSignedCertificate("armeria.dev", Date.from(now),
                                                                        Date.from(notAfter));
            try {
                final MeterRegistry meterRegistry = new SimpleMeterRegistry();
                final ClientFactory factory =
                        ClientFactory.builder()
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

    @Test
    void measureCertificateChainFile() throws CertificateException {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final InputStream certificateChain = getClass()
                .getResourceAsStream(RESOURCE_PATH_PREFIX + "certificate-chain.pem");
        MoreMeterBinders.certificateMetrics(certificateChain, new MeterIdPrefix("valid", "tagA", "value"))
                        .bindTo(registry);

        final String validityName = "valid.tls.certificate.validity";
        final String validityDaysName = "valid.tls.certificate.validity.days";
        assertThatGauge(registry, validityName, "localhost").isOne();
        assertThatGauge(registry, validityDaysName, "localhost").isPositive();
        assertThatGauge(registry, validityName, "test.root.armeria").isOne();
        assertThatGauge(registry, validityDaysName, "test.root.armeria").isPositive();
    }

    @Test
    void measureExpiredCertificate() throws CertificateException {
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        final java.util.Date notAfter = calendar.getTime();
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        final java.util.Date notBefore = calendar.getTime();
        final SelfSignedCertificate ssc = new SelfSignedCertificate("armeria.dev", notBefore, notAfter);

        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MoreMeterBinders.certificateMetrics(ssc.certificate(), new MeterIdPrefix("expired"))
                        .bindTo(registry);
        assertThatGauge(registry, "expired.tls.certificate.validity", "armeria.dev").isZero();
        assertThatGauge(registry, "expired.tls.certificate.validity.days", "armeria.dev").isEqualTo(-1);
    }

    private static AbstractDoubleAssert<?> assertThatGauge(MeterRegistry meterRegistry, String gaugeName,
                                                           String cn, String... tags) {
        final Gauge gauge = meterRegistry.find(gaugeName).tag("common.name", cn).tags(tags).gauge();
        assertThat(gauge).isNotNull();
        return assertThat(gauge.value());
    }
}
