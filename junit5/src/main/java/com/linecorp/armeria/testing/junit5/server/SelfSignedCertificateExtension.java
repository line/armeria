/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.server;

import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Date;

import org.junit.jupiter.api.extension.Extension;

import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;

/**
 * An {@link Extension} that provides a temporary self-signed certificate.
 */
public final class SelfSignedCertificateExtension extends SignedCertificateExtension {

    /**
     * Creates a new instance.
     */
    public SelfSignedCertificateExtension() {
        super(SelfSignedCertificate::new);
    }

    /**
     * Creates a new instance.
     *
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    public SelfSignedCertificateExtension(TemporalAccessor notBefore, TemporalAccessor notAfter) {
        super(() -> new SelfSignedCertificate(toDate(notBefore), toDate(notAfter)));
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    public SelfSignedCertificateExtension(String fqdn) {
        super(() -> new SelfSignedCertificate(fqdn));
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    public SelfSignedCertificateExtension(String fqdn, TemporalAccessor notBefore, TemporalAccessor notAfter) {
        super(() -> new SelfSignedCertificate(fqdn, toDate(notBefore), toDate(notAfter)));
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link SecureRandom} to use
     * @param bits the number of bits of the generated private key
     */
    public SelfSignedCertificateExtension(String fqdn, SecureRandom random, int bits) {
        super(() -> new SelfSignedCertificate(fqdn, random, bits));
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
    public SelfSignedCertificateExtension(String fqdn, SecureRandom random, int bits,
                                          TemporalAccessor notBefore, TemporalAccessor notAfter) {
        super(() -> new SelfSignedCertificate(fqdn, random, bits, toDate(notBefore), toDate(notAfter)));
    }

    @SuppressWarnings("UseOfObsoleteDateTimeApi")
    private static Date toDate(TemporalAccessor temporalAccessor) {
        return new Date(Instant.from(temporalAccessor).toEpochMilli());
    }
}
