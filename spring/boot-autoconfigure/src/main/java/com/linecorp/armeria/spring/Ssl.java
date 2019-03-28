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
/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.spring;

import java.util.List;

import io.netty.handler.ssl.ClientAuth;

/**
 * Simple server-independent abstraction for SSL configuration.
 *
 * @author Andy Wilkinson
 * @author Vladimir Tsanev
 * @author Stephane Nicoll
 */
public class Ssl {
    private boolean enabled = true;

    private ClientAuth clientAuth;

    private List<String> ciphers;

    private List<String> enabledProtocols;

    private String keyAlias;

    private String keyPassword;

    private String keyStore;

    private String keyStorePassword;

    private String keyStoreType;

    private String keyStoreProvider;

    private String trustStore;

    private String trustStorePassword;

    private String trustStoreType;

    private String trustStoreProvider;

    /**
     * Returns whether to enable SSL support.
     * @return whether to enable SSL support
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Enables (or disables) SSL support.
     */
    public Ssl setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Returns whether client authentication is not wanted ("none"), wanted ("want") or
     * needed ("need"). Requires a trust store.
     * @return the {@link ClientAuth} to use
     */
    public ClientAuth getClientAuth() {
        return this.clientAuth;
    }

    /**
     * Sets whether the client authentication is not none ({@link ClientAuth#NONE}), optional
     * ({@link ClientAuth#OPTIONAL}) or required ({@link ClientAuth#REQUIRE}).
     */
    public Ssl setClientAuth(ClientAuth clientAuth) {
        this.clientAuth = clientAuth;
        return this;
    }

    /**
     * Returns the supported SSL ciphers.
     * @return the supported SSL ciphers
     */
    public List<String> getCiphers() {
        return this.ciphers;
    }

    /**
     * Sets the supported SSL ciphers.
     */
    public Ssl setCiphers(List<String> ciphers) {
        this.ciphers = ciphers;
        return this;
    }

    /**
     * Returns the enabled SSL protocols.
     * @return the enabled SSL protocols.
     */
    public List<String> getEnabledProtocols() {
        return this.enabledProtocols;
    }

    /**
     * Sets the enabled SSL protocols.
     */
    public Ssl setEnabledProtocols(List<String> enabledProtocols) {
        this.enabledProtocols = enabledProtocols;
        return this;
    }

    /**
     * Returns the alias that identifies the key in the key store.
     * @return the key alias
     */
    public String getKeyAlias() {
        return this.keyAlias;
    }

    /**
     * Sets the alias that identifies the key in the key store.
     */
    public Ssl setKeyAlias(String keyAlias) {
        this.keyAlias = keyAlias;
        return this;
    }

    /**
     * Returns the password used to access the key in the key store.
     * @return the key password
     */
    public String getKeyPassword() {
        return this.keyPassword;
    }

    /**
     * Sets the password used to access the key in the key store.
     */
    public Ssl setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
        return this;
    }

    /**
     * Returns the path to the key store that holds the SSL certificate (typically a jks
     * file).
     * @return the path to the key store
     */
    public String getKeyStore() {
        return this.keyStore;
    }

    /**
     * Sets the path to the key store that holds the SSL certificate (typically a jks file).
     */
    public Ssl setKeyStore(String keyStore) {
        this.keyStore = keyStore;
        return this;
    }

    /**
     * Returns the password used to access the key store.
     * @return the key store password
     */
    public String getKeyStorePassword() {
        return this.keyStorePassword;
    }

    /**
     * Sets the password used to access the key store.
     */
    public Ssl setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
        return this;
    }

    /**
     * Returns the type of the key store.
     * @return the key store type
     */
    public String getKeyStoreType() {
        return this.keyStoreType;
    }

    /**
     * Sets the type of the key store.
     */
    public Ssl setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
        return this;
    }

    /**
     * Returns the provider for the key store.
     * @return the key store provider
     */
    public String getKeyStoreProvider() {
        return this.keyStoreProvider;
    }

    /**
     * Sets the provider for the key store.
     */
    public Ssl setKeyStoreProvider(String keyStoreProvider) {
        this.keyStoreProvider = keyStoreProvider;
        return this;
    }

    /**
     * Returns the trust store that holds SSL certificates.
     * @return the trust store
     */
    public String getTrustStore() {
        return this.trustStore;
    }

    /**
     * Sets the trust store that holds SSL certificates.
     */
    public Ssl setTrustStore(String trustStore) {
        this.trustStore = trustStore;
        return this;
    }

    /**
     * Returns the password used to access the trust store.
     * @return the trust store password
     */
    public String getTrustStorePassword() {
        return this.trustStorePassword;
    }

    /**
     * Sets the password used to access the trust store.
     */
    public Ssl setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
        return this;
    }

    /**
     * Returns the type of the trust store.
     * @return the trust store type
     */
    public String getTrustStoreType() {
        return this.trustStoreType;
    }

    /**
     * Sets the type of the trust store.
     */
    public Ssl setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
        return this;
    }

    /**
     * Returns the provider for the trust store.
     * @return the trust store provider
     */
    public String getTrustStoreProvider() {
        return this.trustStoreProvider;
    }

    /**
     * Sets the provider for the trust store.
     */
    public Ssl setTrustStoreProvider(String trustStoreProvider) {
        this.trustStoreProvider = trustStoreProvider;
        return this;
    }
}
