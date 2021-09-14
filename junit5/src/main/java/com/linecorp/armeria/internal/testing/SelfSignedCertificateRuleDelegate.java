/*
 *  Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;

/**
 * A delegate that has common testing methods of {@link SelfSignedCertificate}.
 */
public final class SelfSignedCertificateRuleDelegate {

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

    /**
     * Creates a new instance.
     */
    public SelfSignedCertificateRuleDelegate() {
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
    public SelfSignedCertificateRuleDelegate(TemporalAccessor notBefore, TemporalAccessor notAfter) {
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
    public SelfSignedCertificateRuleDelegate(String fqdn) {
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
    public SelfSignedCertificateRuleDelegate(String fqdn,
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
    public SelfSignedCertificateRuleDelegate(String fqdn, SecureRandom random, int bits) {
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
    public SelfSignedCertificateRuleDelegate(String fqdn, SecureRandom random, int bits,
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
    public void before() throws Throwable {
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
    public void after() {
        if (certificate != null) {
            certificate.delete();
        }
    }

    /**
     *  Returns the generated {@link X509Certificate}.
     */
    public X509Certificate certificate() {
        ensureCertificate();
        return certificate.cert();
    }

    /**
     * Returns the self-signed certificate file.
     */
    public File certificateFile() {
        ensureCertificate();
        return certificate.certificate();
    }

    /**
     * Returns the {@link PrivateKey} of the self-signed certificate.
     */
    public PrivateKey privateKey() {
        ensureCertificate();
        return certificate.key();
    }

    /**
     * Returns the private key file of the self-signed certificate.
     */
    public File privateKeyFile() {
        ensureCertificate();
        return certificate.privateKey();
    }

    private void ensureCertificate() {
        checkState(certificate != null, "certificate not created");
    }
}
