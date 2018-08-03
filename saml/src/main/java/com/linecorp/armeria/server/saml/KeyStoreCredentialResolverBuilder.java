/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import org.opensaml.security.credential.CredentialResolver;
import org.opensaml.security.credential.impl.KeyStoreCredentialResolver;

/**
 * A builder class which creates a new {@link KeyStoreCredentialResolver} instance.
 */
public final class KeyStoreCredentialResolverBuilder {

    private final String path;
    private final String keystorePassword;
    private final Map<String, String> keyPasswords = new HashMap<>();

    /**
     * Creates a builder instance for {@link KeyStoreCredentialResolver}.
     */
    public KeyStoreCredentialResolverBuilder(String path, String keystorePassword) {
        this.path = requireNonNull(path, "path");
        this.keystorePassword = requireNonNull(keystorePassword, "keystorePassword");
    }

    /**
     * Adds a key name and its password to the {@link KeyStoreCredentialResolverBuilder}.
     */
    public KeyStoreCredentialResolverBuilder addKeyPassword(String name, String password) {
        requireNonNull(name, "name");
        requireNonNull(password, "password");
        checkArgument(!keyPasswords.containsKey(name), "key already exists: %s", name);
        keyPasswords.put(name, password);
        return this;
    }

    /**
     * Creates a new {@link KeyStoreCredentialResolver}.
     */
    public CredentialResolver build() throws IOException, GeneralSecurityException {
        final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream is = KeyStoreCredentialResolverBuilder.class.getClassLoader()
                                                                     .getResourceAsStream(path)) {
            ks.load(is, keystorePassword.toCharArray());
        }
        return new KeyStoreCredentialResolver(ks, keyPasswords);
    }
}
