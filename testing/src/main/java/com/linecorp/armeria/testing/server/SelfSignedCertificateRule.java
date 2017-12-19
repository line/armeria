/*
 *  Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.testing.server;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;

import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * A {@link TestRule} that provides a temporary self-signed certificate.
 */
public class SelfSignedCertificateRule extends ExternalResource {

    private final String fqdn;
    private final SecureRandom random;
    private final Integer bits;
    private final Date notBefore;
    private final Date notAfter;

    private SelfSignedCertificate certificate;

    /**
     * Creates a new instance.
     */
    public SelfSignedCertificateRule() {
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
    public SelfSignedCertificateRule(Date notBefore, Date notAfter) {
        fqdn = null;
        random = null;
        bits = null;
        this.notBefore = new Date(requireNonNull(notBefore, "notBefore").getTime());
        this.notAfter = new Date(requireNonNull(notAfter, "notAfter").getTime());
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    public SelfSignedCertificateRule(String fqdn) {
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
    public SelfSignedCertificateRule(String fqdn, Date notBefore, Date notAfter) {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        random = null;
        bits = null;
        this.notBefore = new Date(requireNonNull(notBefore, "notBefore").getTime());
        this.notAfter = new Date(requireNonNull(notAfter, "notAfter").getTime());
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link SecureRandom} to use
     * @param bits the number of bits of the generated private key
     */
    public SelfSignedCertificateRule(String fqdn, SecureRandom random, int bits) {
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
    public SelfSignedCertificateRule(String fqdn, SecureRandom random, int bits,
                                     Date notBefore, Date notAfter) {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        this.random = requireNonNull(random, "random");
        this.bits = Integer.valueOf(bits);
        this.notBefore = new Date(requireNonNull(notBefore, "notBefore").getTime());
        this.notAfter = new Date(requireNonNull(notAfter, "notAfter").getTime());
    }

    /**
     * Generates a {@link SelfSignedCertificate}.
     */
    @Override
    protected void before() throws Throwable {
        if (fqdn == null) {
            if (notBefore == null || notAfter == null) {
                certificate = new SelfSignedCertificate();
            } else {
                certificate = new SelfSignedCertificate(notBefore, notAfter);
            }
        } else if (random == null || bits == null) {
            if (notBefore == null || notAfter == null) {
                certificate = new SelfSignedCertificate(fqdn);
            } else {
                certificate = new SelfSignedCertificate(fqdn, notBefore, notAfter);
            }
        } else {
            if (notBefore == null || notAfter == null) {
                certificate = new SelfSignedCertificate(fqdn, random, bits);
            } else {
                certificate = new SelfSignedCertificate(fqdn, random, bits, notBefore, notAfter);
            }
        }
    }

    /**
     * Deletes the generated {@link SelfSignedCertificate}.
     */
    @Override
    protected void after() {
        certificate.delete();
    }

    /**
     *  Returns the generated {@link X509Certificate}.
     */
    public X509Certificate certificate() {
        return certificate.cert();
    }

    /**
     * Returns the self-signed certificate file.
     */
    public File certificateFile() {
        return certificate.certificate();
    }

    /**
     * Returns the {@link PrivateKey} of the self-signed certificate.
     */
    public PrivateKey privateKey() {
        return certificate.key();
    }

    /**
     * Returns the private key file of the self-signed certificate.
     */
    public File privateKeyFile() {
        return certificate.privateKey();
    }
}
