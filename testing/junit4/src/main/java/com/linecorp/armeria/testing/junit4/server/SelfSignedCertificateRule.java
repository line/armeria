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

package com.linecorp.armeria.testing.junit4.server;

import java.io.File;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.temporal.TemporalAccessor;
import java.util.Date;

import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;

import com.linecorp.armeria.internal.testing.SelfSignedCertificateRuleDelegate;

import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * A {@link TestRule} that provides a temporary self-signed certificate.
 */
public final class SelfSignedCertificateRule extends ExternalResource {

    private final SelfSignedCertificateRuleDelegate delegate;

    /**
     * Creates a new instance.
     */
    public SelfSignedCertificateRule() {
        delegate = new SelfSignedCertificateRuleDelegate();
    }

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link #SelfSignedCertificateRule(TemporalAccessor, TemporalAccessor)}.
     *
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    @Deprecated
    @SuppressWarnings("UseOfObsoleteDateTimeApi")
    public SelfSignedCertificateRule(Date notBefore, Date notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(notBefore, notAfter);
    }

    /**
     * Creates a new instance.
     *
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    public SelfSignedCertificateRule(TemporalAccessor notBefore, TemporalAccessor notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(notBefore, notAfter);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    public SelfSignedCertificateRule(String fqdn) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn);
    }

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link #SelfSignedCertificateRule(String, TemporalAccessor, TemporalAccessor)}.
     *
     * @param fqdn a fully qualified domain name
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    @Deprecated
    @SuppressWarnings("UseOfObsoleteDateTimeApi")
    public SelfSignedCertificateRule(String fqdn, Date notBefore, Date notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, notBefore, notAfter);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    public SelfSignedCertificateRule(String fqdn, TemporalAccessor notBefore, TemporalAccessor notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, notBefore, notAfter);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link SecureRandom} to use
     * @param bits the number of bits of the generated private key
     */
    public SelfSignedCertificateRule(String fqdn, SecureRandom random, int bits) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, random, bits);
    }

    /**
     * Creates a new instance.
     *
     * @deprecated Use
     *     {@link #SelfSignedCertificateRule(String, SecureRandom, int, TemporalAccessor, TemporalAccessor)}
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link SecureRandom} to use
     * @param bits the number of bits of the generated private key
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    @Deprecated
    @SuppressWarnings("UseOfObsoleteDateTimeApi")
    public SelfSignedCertificateRule(String fqdn, SecureRandom random, int bits,
                                     Date notBefore, Date notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, random, bits, notBefore, notAfter);
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
                                     TemporalAccessor notBefore, TemporalAccessor notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, random, bits, notBefore, notAfter);
    }

    /**
     * Generates a {@link SelfSignedCertificate}.
     */
    @Override
    protected void before() throws Throwable {
        delegate.before();
    }

    /**
     * Deletes the generated {@link SelfSignedCertificate}.
     */
    @Override
    protected void after() {
        delegate.after();
    }

    /**
     *  Returns the generated {@link X509Certificate}.
     */
    public X509Certificate certificate() {
        return delegate.certificate();
    }

    /**
     * Returns the self-signed certificate file.
     */
    public File certificateFile() {
        return delegate.certificateFile();
    }

    /**
     * Returns the {@link PrivateKey} of the self-signed certificate.
     */
    public PrivateKey privateKey() {
        return delegate.privateKey();
    }

    /**
     * Returns the private key file of the self-signed certificate.
     */
    public File privateKeyFile() {
        return delegate.privateKeyFile();
    }
}
