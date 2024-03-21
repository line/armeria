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

package com.linecorp.armeria.common.metric;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.linecorp.armeria.internal.common.util.CertificateUtil;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class CertificateMetrics implements MeterBinder {

    private final List<X509Certificate> certificates;
    private final MeterIdPrefix meterIdPrefix;

    CertificateMetrics(List<X509Certificate> certificates, MeterIdPrefix meterIdPrefix) {
        this.certificates = certificates;
        this.meterIdPrefix = meterIdPrefix;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (X509Certificate certificate : certificates) {
            final String commonName = firstNonNull(CertificateUtil.getCommonName(certificate), "");

            Gauge.builder(meterIdPrefix.name("tls.certificate.validity"), certificate, x509Cert -> {
                     try {
                         x509Cert.checkValidity();
                     } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                         return 0;
                     }
                     return 1;
                 })
                 .description("1 if TLS certificate is in validity period, 0 if certificate is not in " +
                              "validity period")
                 .tags("common.name", commonName)
                 .tags(meterIdPrefix.tags())
                 .register(registry);

            Gauge.builder(meterIdPrefix.name("tls.certificate.validity.days"), certificate, x509Cert -> {
                     final Duration diff = Duration.between(Instant.now(),
                                                            x509Cert.getNotAfter().toInstant());
                     return diff.isNegative() ? -1 : diff.toDays();
                 })
                 .description("Duration in days before TLS certificate expires, which becomes -1 " +
                              "if certificate is expired")
                 .tags("common.name", commonName)
                 .tags(meterIdPrefix.tags())
                 .register(registry);
        }
    }
}
