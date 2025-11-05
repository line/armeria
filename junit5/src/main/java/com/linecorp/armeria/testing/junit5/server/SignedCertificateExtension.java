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

import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.SignedCertificate;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;

/**
 * An {@link Extension} that provides a temporary signed certificate.
 * Note that the specified {@link SignedCertificateExtension} should be executed before
 * the current extension, optionally be adjusting the order via {@link Order}.
 */
public class SignedCertificateExtension extends AbstractAllOrEachExtension {

    private final ThrowingSupplier<SignedCertificate> certificateFactory;
    @Nullable
    private SignedCertificate signedCertificate;

    /**
     * Creates a new instance.
     */
    @SuppressWarnings("CopyConstructorMissesField")
    public SignedCertificateExtension(SignedCertificateExtension parent) {
        this(() -> new SignedCertificate(parent.signedCertificate()));
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    public SignedCertificateExtension(String fqdn, SignedCertificateExtension parent) {
        this(() -> new SignedCertificate(fqdn, parent.signedCertificate()));
    }

    SignedCertificateExtension(ThrowingSupplier<SignedCertificate> certificateFactory) {
        this.certificateFactory = certificateFactory;
    }

    /**
     * Generates a self-signed certificate.
     */
    @Override
    public void before(ExtensionContext context) throws Exception {
        try {
            signedCertificate = certificateFactory.get();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set up before callback", t);
        }
    }

    /**
     * Deletes the generated self-signed certificate.
     */
    @Override
    public void after(ExtensionContext context) throws Exception {
        final SignedCertificate signedCertificate = this.signedCertificate;
        if (signedCertificate != null) {
            signedCertificate.delete();
        }
    }

    /**
     *  Returns the generated {@link X509Certificate}.
     */
    public X509Certificate certificate() {
        return signedCertificate().cert();
    }

    /**
     * Returns the self-signed certificate file.
     */
    public File certificateFile() {
        return signedCertificate().certificate();
    }

    /**
     * Returns the {@link PrivateKey} of the self-signed certificate.
     */
    public PrivateKey privateKey() {
        return signedCertificate().key();
    }

    /**
     * Returns the private key file of the self-signed certificate.
     */
    public File privateKeyFile() {
        return signedCertificate().privateKey();
    }

    /**
     * Returns the {@link TlsKeyPair} of the self-signed certificate.
     */
    @UnstableApi
    public TlsKeyPair tlsKeyPair() {
        return TlsKeyPair.of(privateKey(), certificate());
    }

    SignedCertificate signedCertificate() {
        final SignedCertificate signedCertificate = this.signedCertificate;
        checkState(signedCertificate != null, "Parent extension '%s' not started yet", signedCertificate);
        return signedCertificate;
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws CertificateException;
    }
}
