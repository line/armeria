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
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.internal.common.util.CertificateUtil;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * A {@link MeterBinder} that provides metrics for TLS certificates.
 * The following stats are currently exported per registered {@link MeterIdPrefix}.
 *
 * <ul>
 *   <li>"tls.certificate.validity" (gauge) - 1 if TLS certificate is in validity period, 0 if certificate
 *       is not in validity period</li>
 *   <li>"tls.certificate.validity.days" (gauge) - Duration in days before TLS certificate expires, which
 *       becomes -1 if certificate is expired</li>
 * </ul>
 */
public final class CertificateMetrics extends AbstractCloseableMeterBinder {

    private final List<X509Certificate> certificates;
    private final MeterIdPrefix meterIdPrefix;

    CertificateMetrics(List<X509Certificate> certificates, MeterIdPrefix meterIdPrefix) {
        this.certificates = certificates;
        this.meterIdPrefix = meterIdPrefix;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        final List<Gauge> meters = new ArrayList<>(certificates.size() * 2);
        for (X509Certificate certificate : certificates) {
            final String hostname = firstNonNull(CertificateUtil.getHostname(certificate), "");

            final Gauge validityMeter =
                    Gauge.builder(meterIdPrefix.name("tls.certificate.validity"), certificate, x509Cert -> {
                             try {
                                 x509Cert.checkValidity();
                             } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                                 return 0;
                             }
                             return 1;
                         })
                         .description(
                                 "1 if TLS certificate is in validity period, 0 if certificate is not in " +
                                 "validity period")
                         .tags("hostname", hostname)
                         .tags(meterIdPrefix.tags())
                         .register(registry);
            meters.add(validityMeter);

            final Gauge validityDaysMeter =
                    Gauge.builder(meterIdPrefix.name("tls.certificate.validity.days"), certificate,
                                  x509Cert -> {
                                      final Instant notAfter = x509Cert.getNotAfter().toInstant();
                                      final Duration diff =
                                              Duration.between(Instant.now(), notAfter);
                                      return diff.toDays();
                                  })
                         .description("Duration in days before TLS certificate expires, which becomes -1 " +
                                      "if certificate is expired")
                         .tags("hostname", hostname)
                         .tags(meterIdPrefix.tags())
                         .register(registry);
            meters.add(validityDaysMeter);
        }

        addClosingTask(() -> {
            for (Gauge meter : meters) {
                registry.remove(meter);
            }
        });
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("certificates", certificates)
                          .add("meterIdPrefix", meterIdPrefix)
                          .toString();
    }
}
