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

package com.linecorp.armeria.testing.junit.server;

import java.io.File;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.temporal.TemporalAccessor;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.internal.testing.SelfSignedCertificateRuleDelegate;
import com.linecorp.armeria.testing.junit.common.AbstractAllOrEachExtension;

import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * An {@link Extension} that provides a temporary self-signed certificate.
 */
public class SelfSignedCertificateExtension extends AbstractAllOrEachExtension {
    private final SelfSignedCertificateRuleDelegate delegate;

    /**
     * Creates a new instance.
     */
    public SelfSignedCertificateExtension() {
        delegate = new SelfSignedCertificateRuleDelegate();
    }

    /**
     * Creates a new instance.
     *
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    public SelfSignedCertificateExtension(TemporalAccessor notBefore, TemporalAccessor notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(notBefore, notAfter);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    public SelfSignedCertificateExtension(String fqdn) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param notBefore {@link Certificate} is not valid before this time
     * @param notAfter {@link Certificate} is not valid after this time
     */
    public SelfSignedCertificateExtension(String fqdn, TemporalAccessor notBefore, TemporalAccessor notAfter) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, notBefore, notAfter);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     * @param random the {@link SecureRandom} to use
     * @param bits the number of bits of the generated private key
     */
    public SelfSignedCertificateExtension(String fqdn, SecureRandom random, int bits) {
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, random, bits);
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
        delegate = new SelfSignedCertificateRuleDelegate(fqdn, random, bits, notBefore, notAfter);
    }

    /**
     * Generates a {@link SelfSignedCertificate}.
     */
    @Override
    public void before(ExtensionContext context) throws Exception {
        try {
            delegate.before();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set up before callback", t);
        }
    }

    /**
     * Deletes the generated {@link SelfSignedCertificate}.
     */
    @Override
    public void after(ExtensionContext context) throws Exception {
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
