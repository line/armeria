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
package com.linecorp.armeria.internal.common.util;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;

public final class CertificateUtil {

    private static final Logger logger = LoggerFactory.getLogger(CertificateUtil.class);

    private static final LoadingCache<X509Certificate, String> commonNameCache =
            Caffeine.newBuilder()
                    .weakKeys()
                    .build(cert -> {
                        try {
                            final X500Name x500Name = new JcaX509CertificateHolder(cert).getSubject();
                            final RDN cn = x500Name.getRDNs(BCStyle.CN)[0];
                            return IETFUtils.valueToString(cn.getFirst().getValue());
                        } catch (Exception e) {
                            logger.warn("Failed to get the common name from a certificate: {}", cert, e);
                            return null;
                        }
                    });

    @Nullable
    public static String getCommonName(SSLSession session) {
        final Certificate[] certs = session.getLocalCertificates();
        if (certs == null || certs.length == 0) {
            return null;
        }
        return getCommonName(certs[0]);
    }

    @Nullable
    public static String getCommonName(Certificate certificate) {
        if (!(certificate instanceof X509Certificate)) {
            return null;
        }
        return commonNameCache.get((X509Certificate) certificate);
    }

    public static List<X509Certificate> toX509Certificates(File file) throws CertificateException {
        requireNonNull(file, "file");
        return ImmutableList.copyOf(SslContextProtectedAccessHack.toX509CertificateList(file));
    }

    public static List<X509Certificate> toX509Certificates(InputStream in) throws CertificateException {
        requireNonNull(in, "in");
        return ImmutableList.copyOf(SslContextProtectedAccessHack.toX509CertificateList(in));
    }

    private static final class SslContextProtectedAccessHack extends SslContext {

        static X509Certificate[] toX509CertificateList(File file) throws CertificateException {
            return SslContext.toX509Certificates(file);
        }

        static X509Certificate[] toX509CertificateList(InputStream in) throws CertificateException {
            return SslContext.toX509Certificates(in);
        }

        @Override
        public boolean isClient() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> cipherSuites() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLEngine newEngine(ByteBufAllocator alloc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLEngine newEngine(ByteBufAllocator alloc, String peerHost, int peerPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLSessionContext sessionContext() {
            throw new UnsupportedOperationException();
        }
    }

    private CertificateUtil() {}
}
