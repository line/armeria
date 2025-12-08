/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.testing.junit4.server;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Date;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;

/**
 * A delegate that has common testing methods of {@link SelfSignedCertificate}.
 */
final class SelfSignedCertificateRuleDelegate {

    @Nullable
    private final String fqdn;
    @Nullable
    private final SecureRandom random;
    @Nullable
    private final Integer bits;
    @Nullable
    private final Instant notBefore;
    @Nullable
    private final Instant notAfter;

    @Nullable
    private SelfSignedCertificate certificate;

    @Nullable
    private TlsKeyPair tlsKeyPair;

    /**
     * Creates a new instance.
     */
    SelfSignedCertificateRuleDelegate() {
        fqdn = null;
        random = null;
        bits = null;
        notBefore = null;
        notAfter = null;
    }

    /**
     * Creates a new instance.
     *
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    SelfSignedCertificateRuleDelegate(TemporalAccessor notBefore, TemporalAccessor notAfter) {
        fqdn = null;
        random = null;
        bits = null;
        this.notBefore = Instant.from(requireNonNull(notBefore, "notBefore"));
        this.notAfter = Instant.from(requireNonNull(notAfter, "notAfter"));
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    SelfSignedCertificateRuleDelegate(String fqdn) {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        random = null;
        bits = null;
        notBefore = null;
        notAfter = null;
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    SelfSignedCertificateRuleDelegate(String fqdn,
                                      TemporalAccessor notBefore, TemporalAccessor notAfter) {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        random = null;
        bits = null;
        this.notBefore = Instant.from(requireNonNull(notBefore, "notBefore"));
        this.notAfter = Instant.from(requireNonNull(notAfter, "notAfter"));
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link SecureRandom} to use
     * @param bits the number of bits of the generated private key
     */
    SelfSignedCertificateRuleDelegate(String fqdn, SecureRandom random, int bits) {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        this.random = requireNonNull(random, "random");
        this.bits = Integer.valueOf(bits);
        notBefore = null;
        notAfter = null;
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link SecureRandom} to use
     * @param bits the number of bits of the generated private key
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    SelfSignedCertificateRuleDelegate(String fqdn, SecureRandom random, int bits,
                                      TemporalAccessor notBefore, TemporalAccessor notAfter) {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        this.random = requireNonNull(random, "random");
        this.bits = Integer.valueOf(bits);
        this.notBefore = Instant.from(requireNonNull(notBefore, "notBefore"));
        this.notAfter = Instant.from(requireNonNull(notAfter, "notAfter"));
    }

    /**
     * Generates a {@link SelfSignedCertificate}.
     */
    void before() throws Throwable {
        if (fqdn == null) {
            if (notBefore == null || notAfter == null) {
                certificate = new SelfSignedCertificate();
            } else {
                certificate = new SelfSignedCertificate(toDate(notBefore), toDate(notAfter));
            }
        } else if (random == null || bits == null) {
            if (notBefore == null || notAfter == null) {
                certificate = new SelfSignedCertificate(fqdn);
            } else {
                certificate = new SelfSignedCertificate(fqdn, toDate(notBefore), toDate(notAfter));
            }
        } else {
            if (notBefore == null || notAfter == null) {
                certificate = new SelfSignedCertificate(fqdn, random, bits);
            } else {
                certificate = new SelfSignedCertificate(fqdn, random, bits,
                                                        toDate(notBefore), toDate(notAfter));
            }
        }
    }

    @SuppressWarnings("UseOfObsoleteDateTimeApi")
    private static Date toDate(Instant notBefore) {
        return new Date(notBefore.toEpochMilli());
    }

    /**
     * Deletes the generated {@link SelfSignedCertificate}.
     */
    void after() {
        if (certificate != null) {
            certificate.delete();
        }
    }

    /**
     *  Returns the generated {@link X509Certificate}.
     */
    X509Certificate certificate() {
        return ensureCertificate().cert();
    }

    /**
     * Returns the self-signed certificate file.
     */
    File certificateFile() {
        return ensureCertificate().certificate();
    }

    /**
     * Returns the {@link PrivateKey} of the self-signed certificate.
     */
    PrivateKey privateKey() {
        return ensureCertificate().key();
    }

    /**
     * Returns the private key file of the self-signed certificate.
     */
    File privateKeyFile() {
        return ensureCertificate().privateKey();
    }

    private SelfSignedCertificate ensureCertificate() {
        checkState(certificate != null, "certificate not created");
        return certificate;
    }
}
