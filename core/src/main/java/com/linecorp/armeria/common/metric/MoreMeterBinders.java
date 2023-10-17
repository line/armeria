/*
 *  Copyright 2023 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.CertificateUtil;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.channel.EventLoopGroup;

/**
 *  Provides useful {@link MeterBinder}s to monitor various Armeria components.
 */
public final class MoreMeterBinders {

    /**
     * Returns a new {@link MeterBinder} to observe Netty's {@link EventLoopGroup}s. The following stats are
     * currently exported per registered {@link MeterIdPrefix}.
     *
     * <ul>
     *   <li>"event.loop.workers" (gauge) - the total number of Netty's event loops</li>
     *   <li>"event.loop.pending.tasks" (gauge)
     *     - the total number of IO tasks waiting to be run on event loops</li>
     * </ul>
     */
    @UnstableApi
    public static MeterBinder eventLoopMetrics(EventLoopGroup eventLoopGroup, String name) {
        requireNonNull(name, "name");
        return eventLoopMetrics(eventLoopGroup, new MeterIdPrefix("armeria.netty." + name));
    }

    /**
     * Returns a new {@link MeterBinder} to observe Netty's {@link EventLoopGroup}s. The following stats are
     * currently exported per registered {@link MeterIdPrefix}.
     *
     * <ul>
     *   <li>"event.loop.workers" (gauge) - the total number of Netty's event loops</li>
     *   <li>"event.loop.pending.tasks" (gauge)
     *     - the total number of IO tasks waiting to be run on event loops</li>
     * </ul>
     */
    @UnstableApi
    public static MeterBinder eventLoopMetrics(EventLoopGroup eventLoopGroup, MeterIdPrefix meterIdPrefix) {
        return new EventLoopMetrics(eventLoopGroup, meterIdPrefix);
    }

    /**
     * Returns a new {@link MeterBinder} to observe the specified {@link X509Certificate}'s validity.
     * The following stats are currently exported per registered {@link MeterIdPrefix}.
     *
     * <ul>
     *   <li>"tls.certificate.validity" (gauge) - 1 if TLS certificate is in validity period, 0 if certificate
     *       is not in validity period</li>
     *   <li>"tls.certificate.validity.days" (gauge) - Duration in days before TLS certificate expires, which
     *       becomes -1 if certificate is expired</li>
     * </ul>
     *
     * @param certificate the certificate to monitor
     * @param meterIdPrefix the prefix to use for all metrics
     */
    @UnstableApi
    public static MeterBinder certificateMetrics(X509Certificate certificate, MeterIdPrefix meterIdPrefix) {
        requireNonNull(certificate, "certificate");
        return certificateMetrics(ImmutableList.of(certificate), meterIdPrefix);
    }

    /**
     * Returns a new {@link MeterBinder} to observe the specified {@link X509Certificate}'s validity.
     * The following stats are currently exported per registered {@link MeterIdPrefix}.
     *
     * <ul>
     *   <li>"tls.certificate.validity" (gauge) - 1 if TLS certificate is in validity period, 0 if certificate
     *       is not in validity period</li>
     *   <li>"tls.certificate.validity.days" (gauge) - Duration in days before TLS certificate expires, which
     *       becomes -1 if certificate is expired</li>
     * </ul>
     *
     * @param certificates the certificates to monitor
     * @param meterIdPrefix the prefix to use for all metrics
     */
    @UnstableApi
    public static MeterBinder certificateMetrics(Iterable<? extends X509Certificate> certificates,
                                                 MeterIdPrefix meterIdPrefix) {
        requireNonNull(certificates, "certificates");
        requireNonNull(meterIdPrefix, "meterIdPrefix");
        return new CertificateMetrics(ImmutableList.copyOf(certificates), meterIdPrefix);
    }

    /**
     * Returns a new {@link MeterBinder} to observe the {@link X509Certificate}'s validity in the PEM format
     * {@link File}. The following stats are currently exported per registered {@link MeterIdPrefix}.
     *
     * <ul>
     *   <li>"tls.certificate.validity" (gauge) - 1 if TLS certificate is in validity period, 0 if certificate
     *       is not in validity period</li>
     *   <li>"tls.certificate.validity.days" (gauge) - Duration in days before TLS certificate expires, which
     *       becomes -1 if certificate is expired</li>
     * </ul>
     *
     * @param keyCertChainFile the certificates to monitor
     * @param meterIdPrefix the prefix to use for all metrics
     */
    @UnstableApi
    public static MeterBinder certificateMetrics(File keyCertChainFile, MeterIdPrefix meterIdPrefix)
            throws CertificateException {
        requireNonNull(keyCertChainFile, "keyCertChainFile");
        return certificateMetrics(CertificateUtil.toX509Certificates(keyCertChainFile), meterIdPrefix);
    }

    /**
     * Returns a new {@link MeterBinder} to observe the {@link X509Certificate}'s validity in the PEM format
     * {@link InputStream}. The following stats are currently exported per registered {@link MeterIdPrefix}.
     *
     * <ul>
     *   <li>"tls.certificate.validity" (gauge) - 1 if TLS certificate is in validity period, 0 if certificate
     *       is not in validity period</li>
     *   <li>"tls.certificate.validity.days" (gauge) - Duration in days before TLS certificate expires, which
     *       becomes -1 if certificate is expired</li>
     * </ul>
     *
     * @param keyCertChainFile the certificates to monitor
     * @param meterIdPrefix the prefix to use for all metrics
     */
    @UnstableApi
    public static MeterBinder certificateMetrics(InputStream keyCertChainFile, MeterIdPrefix meterIdPrefix)
            throws CertificateException {
        requireNonNull(keyCertChainFile, "keyCertChainFile");
        return certificateMetrics(CertificateUtil.toX509Certificates(keyCertChainFile), meterIdPrefix);
    }

    private MoreMeterBinders() {}
}
