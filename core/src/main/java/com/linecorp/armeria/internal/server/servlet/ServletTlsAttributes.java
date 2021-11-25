/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.server.servlet;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.BiConsumer;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.internal.EmptyArrays;

/**
 * Fills the Servlet TLS attributes from {@link SSLSession} into {@code ServletRequest}.
 * See {@code TomcatService} and {@code JettyService}.
 */
public final class ServletTlsAttributes {

    private static final String JAVAX_SERVLET_REQUEST_SSL_SESSION_ID =
            "javax.servlet.request.ssl_session_id";
    private static final String JAVAX_SERVLET_REQUEST_CIPHER_SUITE =
            "javax.servlet.request.cipher_suite";
    private static final String JAVAX_SERVLET_REQUEST_KEY_SIZE =
            "javax.servlet.request.key_size";
    private static final String JAVAX_SERVLET_REQUEST_X_509_CERTIFICATE =
            "javax.servlet.request.X509Certificate";

    private static final String ATTR_NAME = ServletTlsAttributes.class.getName();

    /**
     * An array of well known algorithms where each algorithm's key size is the value of {@link #KEY_SIZES}
     * at the same index.
     *
     * @see #guessKeySize(String)
     */
    private static final String[] ALGORITHMS = {
            "_AES_256_", "_RC4_128_", "_AES_128_", "_CHACHA20_",
            "_ARIA256_", "_ARIA128_", "_CAMELLIA256_", "_CAMELLIA128_",
            "_RC4_40_", "_3DES_EDE_CBC_", "_IDEA_CBC_", "_RC2_CBC_40_",
            "_DES40_CBC_", "_DES_CBC_", "_SEED_"
    };

    /**
     * An array of key sizes for each algorithm in {@link #ALGORITHMS}.
     *
     * @see #guessKeySize(String)
     */
    private static final int[] KEY_SIZES = {
            256, 128, 128, 256,
            256, 128, 256, 128,
            40, 168, 128, 40,
            40, 56, 128
    };

    /**
     * Fills the Servlet TLS attributes from {@link SSLSession} into {@code ServletRequest}.
     *
     * @param session the {@link SSLSession} of the current connection.
     * @param setter the setter that will be invoked with the Servlet attribute name and value.
     */
    public static void fill(@Nullable SSLSession session, BiConsumer<String, Object> setter) {
        if (session == null) {
            return;
        }

        final ServletTlsAttributes attrs = getOrCreateAttrs(session);
        final String sessionId = attrs.sessionId();
        final String cipherSuite = attrs.cipherSuite();

        final int keySize = attrs.keySize();
        final List<X509Certificate> peerCerts = attrs.peerCertificates();
        setter.accept(JAVAX_SERVLET_REQUEST_SSL_SESSION_ID, sessionId);
        setter.accept(JAVAX_SERVLET_REQUEST_CIPHER_SUITE, cipherSuite);
        setter.accept(JAVAX_SERVLET_REQUEST_KEY_SIZE, keySize);

        if (!peerCerts.isEmpty()) {
            setter.accept(JAVAX_SERVLET_REQUEST_X_509_CERTIFICATE,
                          peerCerts.toArray(EmptyArrays.EMPTY_X509_CERTIFICATES));
        }
    }

    private static ServletTlsAttributes getOrCreateAttrs(SSLSession session) {
        ServletTlsAttributes attrs = (ServletTlsAttributes) session.getValue(ATTR_NAME);
        if (attrs != null) {
            return attrs;
        }

        synchronized (session) {
            attrs = (ServletTlsAttributes) session.getValue(ATTR_NAME);
            if (attrs == null) {
                final byte[] sessionIdBytes = session.getId();
                final String sessionId = sessionIdBytes != null ? BaseEncoding.base16().encode(sessionIdBytes)
                                                                : "";
                final String cipherSuite = session.getCipherSuite();

                attrs = new ServletTlsAttributes(
                        sessionId,
                        firstNonNull(cipherSuite, ""),
                        guessKeySize(cipherSuite),
                        getPeerX509Certificates(session));

                session.putValue(ATTR_NAME, attrs);
            }
            return attrs;
        }
    }

    @VisibleForTesting
    static int guessKeySize(@Nullable String cipherSuite) {
        if (cipherSuite == null) {
            return 0;
        }

        final int withIdx = cipherSuite.indexOf("_WITH_");
        final int startIdx;
        if (withIdx > 0) {
            startIdx = withIdx + 5; // Skip "_WITH" in the middle.
        } else {
            startIdx = cipherSuite.indexOf('_'); // Skip the first component ("TLS" or "SSL").
            if (startIdx < 0) {
                return 0;
            }
        }

        for (int i = 0; i < ALGORITHMS.length; i++) {
            if (cipherSuite.startsWith(ALGORITHMS[i], startIdx)) {
                return KEY_SIZES[i];
            }
        }

        // Unknown algorithm
        return 0;
    }

    private static List<X509Certificate> getPeerX509Certificates(SSLSession session) {
        try {
            final Certificate[] certs = session.getPeerCertificates();
            if (certs == null) {
                return ImmutableList.of();
            }

            final ImmutableList.Builder<X509Certificate> builder =
                    ImmutableList.builderWithExpectedSize(certs.length);
            for (Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    builder.add((X509Certificate) c);
                }
            }

            return builder.build();
        } catch (SSLPeerUnverifiedException ignored) {
            return ImmutableList.of();
        }
    }

    private final String sessionId;
    private final String cipherSuite;
    private final int keySize;
    private final List<X509Certificate> peerCertificates;

    private ServletTlsAttributes(String sessionId, String cipherSuite, int keySize,
                                 List<X509Certificate> peerCertificates) {
        this.sessionId = sessionId;
        this.cipherSuite = cipherSuite;
        this.keySize = keySize;
        this.peerCertificates = peerCertificates;
    }

    public String sessionId() {
        return sessionId;
    }

    public String cipherSuite() {
        return cipherSuite;
    }

    public int keySize() {
        return keySize;
    }

    public List<X509Certificate> peerCertificates() {
        return peerCertificates;
    }
}
