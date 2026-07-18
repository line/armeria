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
import java.security.KeyException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;

import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
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
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;

public final class CertificateUtil {

    private static final Logger logger = LoggerFactory.getLogger(CertificateUtil.class);

    // A sentinel cached for a certificate without a hostname, because Caffeine does not cache null
    // loader results. `weakKeys()` compares keys by identity, so a different instance of an equal
    // certificate computes and logs once again.
    private static final String NO_HOSTNAME = "";

    private static final LoadingCache<X509Certificate, String> hostnameCache =
            Caffeine.newBuilder()
                    .weakKeys()
                    .build(cert -> {
                        try {
                            final String san = extractSubjectAlternativeName(cert);
                            if (san != null && !san.isEmpty()) {
                                return san;
                            }
                            final String commonName = extractCommonName(cert);
                            if (commonName != null && !commonName.isEmpty()) {
                                return commonName;
                            }

                            // Public root CA certificates may have neither a CN nor a SAN.
                            // Fall back to the subject DN so that such certificates are still
                            // distinguishable in metrics.
                            final String subjectDn = cert.getSubjectX500Principal().getName();
                            if (!subjectDn.isEmpty()) {
                                return subjectDn;
                            }

                            logger.debug("No subject alternative name, common name or subject " +
                                         "distinguished name found in certificate: {}", cert);
                            return NO_HOSTNAME;
                        } catch (Exception e) {
                            logger.warn("Failed to get the hostname from a certificate: {}", cert, e);
                            return NO_HOSTNAME;
                        }
                    });

    @Nullable
    private static String extractCommonName(X509Certificate cert) throws CertificateEncodingException {
        final X500Name x500Name = new JcaX509CertificateHolder(cert).getSubject();
        final RDN[] cns = x500Name.getRDNs(BCStyle.CN);
        if (cns == null || cns.length == 0) {
            return null;
        }

        final AttributeTypeAndValue cn = cns[0].getFirst();
        if (cn == null) {
            return null;
        }
        return IETFUtils.valueToString(cn.getValue());
    }

    @Nullable
    private static String extractSubjectAlternativeName(X509Certificate cert) {
        try {
            final Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames == null) {
                return null;
            }
            for (final List<?> altName : altNames) {
                final Integer type = altName.size() >= 2 ? (Integer) altName.get(0) : null;
                if (type != null) {
                    // Type 2 is DNS name, type 7 is IP address.
                    if (type == 2 || type == 7) {
                        final Object o = altName.get(1);
                        if (o instanceof String) {
                            return (String) o;
                        }
                    }
                }
            }
            return null;
        } catch (CertificateParsingException ex) {
            logger.warn("Failed to parse subject alternative names from a certificate: {}", cert, ex);
            return null;
        }
    }

    public static List<String> extractDnsNames(X509Certificate cert) {
        requireNonNull(cert, "cert");
        try {
            final Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                final ImmutableList.Builder<String> dnsNames = ImmutableList.builder();
                for (List<?> altName : altNames) {
                    final Integer type = altName.size() >= 2 ? (Integer) altName.get(0) : null;
                    // Type 2 is DNS name.
                    if (type != null && type == 2) {
                        dnsNames.add(((String) altName.get(1)).toLowerCase(Locale.ROOT));
                    }
                }
                return dnsNames.build();
            }
        } catch (CertificateParsingException e) {
            return Exceptions.throwUnsafely(e);
        }
        try {
            final String cn = extractCommonName(cert);
            if (cn != null) {
                return ImmutableList.of(cn.toLowerCase(Locale.ROOT));
            }
        } catch (CertificateEncodingException e) {
            return Exceptions.throwUnsafely(e);
        }
        return ImmutableList.of();
    }

    @Nullable
    public static String getHostname(SSLSession session) {
        final Certificate[] certs = session.getLocalCertificates();
        if (certs == null || certs.length == 0) {
            return null;
        }
        return getHostname(certs[0]);
    }

    /**
     * Returns the hostname of the specified certificate, or {@code null} if none can be determined.
     *
     * <p>The hostname is resolved from the subject alternative name, falling back to the common name and
     * then to the RFC 2253 subject distinguished name. The distinguished name is not a hostname, but it
     * keeps otherwise-indistinguishable certificates - such as CA certificates that have neither a common
     * name nor a subject alternative name - distinct when used as a metric tag.
     */
    @Nullable
    public static String getHostname(Certificate certificate) {
        if (!(certificate instanceof X509Certificate)) {
            return null;
        }
        final String hostname = hostnameCache.get((X509Certificate) certificate);
        return NO_HOSTNAME.equals(hostname) ? null : hostname;
    }

    public static List<X509Certificate> toX509Certificates(File file) throws CertificateException {
        requireNonNull(file, "file");
        return ImmutableList.copyOf(SslContextProtectedAccessHack.toX509CertificateList(file));
    }

    public static List<X509Certificate> toX509Certificates(InputStream in) throws CertificateException {
        requireNonNull(in, "in");
        return ImmutableList.copyOf(SslContextProtectedAccessHack.toX509CertificateList(in));
    }

    public static PrivateKey toPrivateKey(File file, @Nullable String keyPassword) throws KeyException {
        requireNonNull(file, "file");
        return MinifiedBouncyCastleProvider.call(() -> {
            try {
                return SslContextProtectedAccessHack.privateKey(file, keyPassword);
            } catch (KeyException e) {
                return Exceptions.throwUnsafely(e);
            }
        });
    }

    public static PrivateKey toPrivateKey(InputStream keyInputStream, @Nullable String keyPassword)
            throws KeyException {
        requireNonNull(keyInputStream, "keyInputStream");
        return MinifiedBouncyCastleProvider.call(() -> {
            try {
                return SslContextProtectedAccessHack.privateKey(keyInputStream, keyPassword);
            } catch (KeyException e) {
                return Exceptions.throwUnsafely(e);
            }
        });
    }

    private static final class SslContextProtectedAccessHack extends SslContext {

        static X509Certificate[] toX509CertificateList(File file) throws CertificateException {
            return SslContext.toX509Certificates(file);
        }

        static X509Certificate[] toX509CertificateList(InputStream in) throws CertificateException {
            return SslContext.toX509Certificates(in);
        }

        static PrivateKey privateKey(File file, @Nullable String keyPassword) throws KeyException {
            try {
                return SslContext.toPrivateKey(file, keyPassword);
            } catch (Exception e) {
                if (e instanceof KeyException) {
                    throw (KeyException) e;
                }
                throw new KeyException("Fail to read a private key file: " + file.getName(), e);
            }
        }

        static PrivateKey privateKey(InputStream keyInputStream, @Nullable String keyPassword)
                throws KeyException {
            try {
                return SslContext.toPrivateKey(keyInputStream, keyPassword);
            } catch (Exception e) {
                if (e instanceof KeyException) {
                    throw (KeyException) e;
                }
                throw new KeyException("Fail to parse a private key", e);
            }
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
