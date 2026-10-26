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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import org.opensaml.security.credential.CredentialResolver;
import org.opensaml.security.credential.impl.KeyStoreCredentialResolver;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A builder class which creates a new {@link KeyStoreCredentialResolver} instance.
 */
public final class KeyStoreCredentialResolverBuilder {
    @Nullable
    private final File file;
    @Nullable
    private final String resourcePath;
    @Nullable
    private final ClassLoader classLoader;

    private String type = KeyStore.getDefaultType();
    @Nullable
    private String password;
    private final Map<String, String> keyPasswords = new HashMap<>();

    /**
     * Creates a builder with the specified {@link File}.
     */
    public KeyStoreCredentialResolverBuilder(File file) {
        this.file = requireNonNull(file, "file");
        resourcePath = null;
        classLoader = null;
    }

    /**
     * Creates a builder with the file at the specified {@link Path}.
     */
    public KeyStoreCredentialResolverBuilder(Path path) {
        this(requireNonNull(path, "path").toFile());
    }

    /**
     * Creates a builder with the specified {@link ClassLoader} and {@code resourcePath}.
     */
    public KeyStoreCredentialResolverBuilder(ClassLoader classLoader, String resourcePath) {
        this.classLoader = requireNonNull(classLoader, "classLoader");
        this.resourcePath = requireNonNull(resourcePath, "resourcePath");
        file = null;
    }

    /**
     * Sets a type of the {@link KeyStore}. If not set, the default value retrieved from
     * {@link KeyStore#getDefaultType()} will be used.
     */
    public KeyStoreCredentialResolverBuilder type(String type) {
        this.type = requireNonNull(type, "type");
        return this;
    }

    /**
     * Sets a password of the {@link KeyStore}.
     */
    public KeyStoreCredentialResolverBuilder password(@Nullable String password) {
        this.password = password;
        return this;
    }

    /**
     * Adds a key name and its password to the {@link KeyStoreCredentialResolverBuilder}.
     */
    public KeyStoreCredentialResolverBuilder keyPassword(String name, String password) {
        requireNonNull(name, "name");
        requireNonNull(password, "password");
        checkArgument(!keyPasswords.containsKey(name), "key already exists: %s", name);
        keyPasswords.put(name, password);
        return this;
    }

    /**
     * Adds all key names and their passwords which are specified by the {@code keyPasswords}.
     */
    public KeyStoreCredentialResolverBuilder keyPasswords(Map<String, String> keyPasswords) {
        requireNonNull(keyPasswords, "keyPasswords");
        keyPasswords.forEach(this::keyPassword);
        return this;
    }

    /**
     * Creates a new {@link KeyStoreCredentialResolver}.
     */
    public CredentialResolver build() throws IOException, GeneralSecurityException {
        final KeyStore ks = KeyStore.getInstance(type);
        try (InputStream is = open()) {
            ks.load(is, password != null ? password.toCharArray() : null);
        }
        return new KeyStoreCredentialResolver(ks, keyPasswords);
    }

    private InputStream open() throws IOException {
        if (file != null) {
            return new FileInputStream(file);
        }

        ClassLoader classLoader = this.classLoader;
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }

        assert classLoader != null : "classLoader";
        assert resourcePath != null : "resourcePath";
        final InputStream is = classLoader.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new FileNotFoundException("Resource not found: " + resourcePath);
        }
        return is;
    }
}
