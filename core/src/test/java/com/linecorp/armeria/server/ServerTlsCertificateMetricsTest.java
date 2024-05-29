/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.cert.CertificateException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;

import org.assertj.core.api.AbstractDoubleAssert;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

public class ServerTlsCertificateMetricsTest {

    private static final String RESOURCE_PATH_PREFIX =
            "/testing/core/" + ServerTlsCertificateMetricsTest.class.getSimpleName() + '/';

    private static final String CERT_VALIDITY_GAUGE_NAME = "armeria.server.tls.certificate.validity";
    private static final String CERT_VALIDITY_DAYS_GAUGE_NAME = "armeria.server.tls.certificate.validity.days";

    @Test
    void noTlsMetricGivenNoTlsSetup() {
        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
        Server.builder()
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .meterRegistry(meterRegistry)
              .build();

        final Gauge expirationGauge = meterRegistry.find(CERT_VALIDITY_GAUGE_NAME).gauge();
        final Gauge timeToExpireGauge = meterRegistry.find(CERT_VALIDITY_DAYS_GAUGE_NAME).gauge();

        assertThat(expirationGauge).isNull();
        assertThat(timeToExpireGauge).isNull();
    }

    @Test
    void tlsMetricGivenConfiguredCertificateNotExpired() throws CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate("localhost");
        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
        Server.builder()
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .meterRegistry(meterRegistry)
              .tls(ssc.certificate(), ssc.privateKey())
              .build();

        assertThatGauge(meterRegistry, CERT_VALIDITY_GAUGE_NAME, "localhost").isOne();
        assertThatGauge(meterRegistry, CERT_VALIDITY_DAYS_GAUGE_NAME, "localhost").isPositive();
    }

    @Test
    void tlsMetricGivenSelfSignCertificateNotExpired() {
        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
        Server.builder()
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .meterRegistry(meterRegistry)
              .tlsSelfSigned()
              .build();

        final Gauge expirationGauge = meterRegistry.find(CERT_VALIDITY_GAUGE_NAME).gauge();
        assertThat(expirationGauge).isNotNull();
        assertThat(expirationGauge.value()).isOne();

        final Gauge timeToExpireGauge = meterRegistry.find(CERT_VALIDITY_DAYS_GAUGE_NAME).gauge();
        assertThat(timeToExpireGauge).isNotNull();
        assertThat(timeToExpireGauge.value()).isPositive();
    }

    @Test
    void tlsMetricGivenVirtualHostCertificateNotExpired() throws CertificateException {
        final String commonName = "*.virtual.com";
        final String hostnamePattern = "foo.virtual.com";
        final SelfSignedCertificate ssc = new SelfSignedCertificate(commonName);
        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();

        Server.builder()
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .meterRegistry(meterRegistry)
              .tls(ssc.certificate(), ssc.privateKey())
              .virtualHost(hostnamePattern)
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .tlsSelfSigned().and()
              .build();

        final Collection<Gauge> validityGauges = meterRegistry.find(CERT_VALIDITY_GAUGE_NAME).gauges();
        final Collection<Gauge> daysValidityGauges = meterRegistry.find(CERT_VALIDITY_DAYS_GAUGE_NAME).gauges();
        // One gauge set for defaultVirtualHost and another for *.virtual.com
        assertThat(validityGauges.size()).isEqualTo(2);
        assertThat(daysValidityGauges.size()).isEqualTo(2);

        assertThat(meterRegistry.find(CERT_VALIDITY_GAUGE_NAME)
                                .tag("common.name", commonName)
                                .tag("hostname.pattern", "*") // default virtual host
                                .gauge().value()).isOne();
        assertThat(meterRegistry.find(CERT_VALIDITY_GAUGE_NAME)
                                .tag("common.name", commonName)
                                .tag("hostname.pattern", hostnamePattern) // non-default virtual host
                                .gauge().value()).isOne();
        assertThat(meterRegistry.find(CERT_VALIDITY_DAYS_GAUGE_NAME)
                                .tag("common.name", commonName)
                                .tag("hostname.pattern", "*") // default virtual host
                                .gauge().value()).isPositive();
        assertThat(meterRegistry.find(CERT_VALIDITY_DAYS_GAUGE_NAME)
                                .tag("common.name", commonName)
                                .tag("hostname.pattern", hostnamePattern) // non-default virtual host
                                .gauge().value()).isPositive();
    }

    @Test
    void tlsMetricGivenCertificateChainNotExpired() {
        final InputStream certificateChain = getClass().getResourceAsStream(
                RESOURCE_PATH_PREFIX + "certificate-chain.pem");
        final InputStream pk = getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + "pk.key");

        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
        Server.builder()
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .meterRegistry(meterRegistry)
              .tls(certificateChain, pk)
              .build();

        assertThatGauge(meterRegistry, CERT_VALIDITY_GAUGE_NAME, "localhost").isOne();
        assertThatGauge(meterRegistry, CERT_VALIDITY_DAYS_GAUGE_NAME, "localhost").isPositive();
        assertThatGauge(meterRegistry, CERT_VALIDITY_GAUGE_NAME, "test.root.armeria").isOne();
        assertThatGauge(meterRegistry, CERT_VALIDITY_DAYS_GAUGE_NAME, "test.root.armeria").isPositive();
    }

    @Test
    void tlsMetricGivenExpired() throws CertificateException {
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        final Date notAfter = calendar.getTime();
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        final Date notBefore = calendar.getTime();
        final SelfSignedCertificate ssc = new SelfSignedCertificate("localhost", notBefore, notAfter);

        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
        Server.builder()
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .meterRegistry(meterRegistry)
              .tls(ssc.certificate(), ssc.privateKey())
              .build();

        assertThatGauge(meterRegistry, CERT_VALIDITY_GAUGE_NAME, "localhost").isZero();
        assertThatGauge(meterRegistry, CERT_VALIDITY_DAYS_GAUGE_NAME, "localhost").isEqualTo(-1);
    }

    @Test
    void tlsMetricGivenCertificateChainExpired() {
        final InputStream expiredCertificateChain = getClass()
                .getResourceAsStream(RESOURCE_PATH_PREFIX + "expired-certificate-chain.pem");
        final InputStream pk = getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + "expire-pk.key");

        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
        Server.builder()
              .service("/", (ctx, req) -> HttpResponse.of(200))
              .meterRegistry(meterRegistry)
              .tls(expiredCertificateChain, pk)
              .build();

        assertThatGauge(meterRegistry, CERT_VALIDITY_GAUGE_NAME, "localhost").isZero();
        assertThatGauge(meterRegistry, CERT_VALIDITY_DAYS_GAUGE_NAME, "localhost").isEqualTo(-1);
        assertThatGauge(meterRegistry, CERT_VALIDITY_GAUGE_NAME, "test.root.armeria").isOne();
        assertThatGauge(meterRegistry, CERT_VALIDITY_DAYS_GAUGE_NAME, "test.root.armeria").isPositive();
    }

    private static AbstractDoubleAssert<?> assertThatGauge(MeterRegistry meterRegistry, String gaugeName,
                                                           String cn) {
        final Gauge gauge = meterRegistry.find(gaugeName).tag("common.name", cn).gauge();
        assertThat(gauge).isNotNull();
        return assertThat(gauge.value());
    }
}
