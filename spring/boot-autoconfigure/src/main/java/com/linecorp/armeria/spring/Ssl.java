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
     * Return Whether client authentication is not wanted ("none"), wanted ("want") or
     * needed ("need"). Requires a trust store.
     * @return the {@link ClientAuth} to use
     */
    public ClientAuth getClientAuth() {
        return this.clientAuth;
    }

    public Ssl setClientAuth(ClientAuth clientAuth) {
        this.clientAuth = clientAuth;
        return this;
    }

    /**
     * Return the supported SSL ciphers.
     * @return the supported SSL ciphers
     */
    public List<String> getCiphers() {
        return this.ciphers;
    }

    public Ssl setCiphers(List<String> ciphers) {
        this.ciphers = ciphers;
        return this;
    }

    /**
     * Return the enabled SSL protocols.
     * @return the enabled SSL protocols.
     */
    public List<String> getEnabledProtocols() {
        return this.enabledProtocols;
    }

    public Ssl setEnabledProtocols(List<String> enabledProtocols) {
        this.enabledProtocols = enabledProtocols;
        return this;
    }

    /**
     * Return the alias that identifies the key in the key store.
     * @return the key alias
     */
    public String getKeyAlias() {
        return this.keyAlias;
    }

    public Ssl setKeyAlias(String keyAlias) {
        this.keyAlias = keyAlias;
        return this;
    }

    /**
     * Return the password used to access the key in the key store.
     * @return the key password
     */
    public String getKeyPassword() {
        return this.keyPassword;
    }

    public Ssl setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
        return this;
    }

    /**
     * Return the path to the key store that holds the SSL certificate (typically a jks
     * file).
     * @return the path to the key store
     */
    public String getKeyStore() {
        return this.keyStore;
    }

    public Ssl setKeyStore(String keyStore) {
        this.keyStore = keyStore;
        return this;
    }

    /**
     * Return the password used to access the key store.
     * @return the key store password
     */
    public String getKeyStorePassword() {
        return this.keyStorePassword;
    }

    public Ssl setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
        return this;
    }

    /**
     * Return the type of the key store.
     * @return the key store type
     */
    public String getKeyStoreType() {
        return this.keyStoreType;
    }

    public Ssl setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
        return this;
    }

    /**
     * Return the provider for the key store.
     * @return the key store provider
     */
    public String getKeyStoreProvider() {
        return this.keyStoreProvider;
    }

    public Ssl setKeyStoreProvider(String keyStoreProvider) {
        this.keyStoreProvider = keyStoreProvider;
        return this;
    }

    /**
     * Return the trust store that holds SSL certificates.
     * @return the trust store
     */
    public String getTrustStore() {
        return this.trustStore;
    }

    public Ssl setTrustStore(String trustStore) {
        this.trustStore = trustStore;
        return this;
    }

    /**
     * Return the password used to access the trust store.
     * @return the trust store password
     */
    public String getTrustStorePassword() {
        return this.trustStorePassword;
    }

    public Ssl setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
        return this;
    }

    /**
     * Return the type of the trust store.
     * @return the trust store type
     */
    public String getTrustStoreType() {
        return this.trustStoreType;
    }

    public Ssl setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
        return this;
    }

    /**
     * Return the provider for the trust store.
     * @return the trust store provider
     */
    public String getTrustStoreProvider() {
        return this.trustStoreProvider;
    }

    public Ssl setTrustStoreProvider(String trustStoreProvider) {
        this.trustStoreProvider = trustStoreProvider;
        return this;
    }
}
