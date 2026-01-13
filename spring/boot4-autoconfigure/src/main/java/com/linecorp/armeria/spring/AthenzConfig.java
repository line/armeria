/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.spring;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Configuration for a Athenz provider.
 * If this is set, you can use `@RequiresAthenzRole` annotation to secure your service methods.
 */
@UnstableApi
public final class AthenzConfig {
    /**
     * The URI of the ZTS server that the Athenz client connects to.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    @Nullable
    private URI ztsUri;

    /**
     * The proxy URI for the ZTS client.
     * Default is {@code null}, which means no proxy.
     */
    @Nullable
    private URI proxyUri;

    /**
     * The Athenz service private key file for mutual TLS authentication.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    @Nullable
    private File athenzPrivateKey;

    /**
     * The Athenz service public key file for mutual TLS authentication.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    @Nullable
    private File athenzPublicKey;

    /**
     * The Athenz CA certificate file for verifying the ZTS server's certificate.
     * If not set, the default system CA certificates will be used.
     */
    @Nullable
    private File athenzCaCert;

    /**
     * The path of the Athenz JWKS endpoint used for OAuth2 token verification.
     * Defaults to {@code "/oauth2/keys?rfc=true"} if not specified.
     */
    @Nullable
    private String oauth2KeysPath;

    /**
     * The list of domains to fetch Athenz policies for.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    private List<String> domains = new ArrayList<>();

    /**
     * Whether to support JWS policy.
     * The default is {@code true}.
     */
    private boolean jwsPolicySupport = true;

    /**
     * The interval for refreshing the Athenz policy data.
     * The default is 1 hour.
     */
    private Duration policyRefreshInterval = Duration.ofHours(1);

    /**
     * Whether to enable metrics collection for Athenz {@code ZtsBaseClient}.
     * The default is {@code true}.
     */
    private boolean enableMetrics = true;

    /**
     * The meter ID prefix for Athenz {@code ZtsBaseClient} metrics.
     * The default is {@code "armeria.athenz.zts.client"}.
     */
    private String meterIdPrefix = "armeria.athenz.zts.client";

    /**
     * Returns the URI of the ZTS server that the Athenz client connects to.
     */
    @Nullable
    public URI getZtsUri() {
        return ztsUri;
    }

    /**
     * Sets the URI of the ZTS server that the Athenz client connects to.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    public void setZtsUri(URI ztsUri) {
        this.ztsUri = requireNonNull(ztsUri, "ztsUri");
    }

    /**
     * Returns the proxy URI for the ZTS client.
     */
    @Nullable
    public URI getProxyUri() {
        return proxyUri;
    }

    /**
     * Sets the proxy URI for the ZTS client.
     */
    public void setProxyUri(@Nullable URI proxyUri) {
        this.proxyUri = proxyUri;
    }

    /**
     * Returns the Athenz service private key file for mutual TLS authentication.
     */
    @Nullable
    public File getAthenzPrivateKey() {
        return athenzPrivateKey;
    }

    /**
     * Sets the Athenz service private key file for mutual TLS authentication.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    public void setAthenzPrivateKey(File athenzPrivateKey) {
        this.athenzPrivateKey = requireNonNull(athenzPrivateKey, "athenzPrivateKey");
    }

    /**
     * Returns the Athenz service public key file for mutual TLS authentication.
     */
    @Nullable
    public File getAthenzPublicKey() {
        return athenzPublicKey;
    }

    /**
     * Sets the Athenz service public key file for mutual TLS authentication.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    public void setAthenzPublicKey(File athenzPublicKey) {
        this.athenzPublicKey = requireNonNull(athenzPublicKey, "athenzPublicKey");
    }

    /**
     * Returns the Athenz CA certificate file for verifying the ZTS server's certificate.
     * If not set, the default system CA certificates will be used.
     */
    @Nullable
    public File getAthenzCaCert() {
        return athenzCaCert;
    }

    /**
     * Sets the Athenz CA certificate file for verifying the ZTS server's certificate.
     * If not set, the default system CA certificates will be used.
     */
    public void setAthenzCaCert(@Nullable File athenzCaCert) {
        this.athenzCaCert = athenzCaCert;
    }

    /**
     * Returns the path of the Athenz JWKS endpoint used for OAuth2 token verification.
     * Defaults to {@code "/oauth2/keys?rfc=true"} if not specified.
     */
    @Nullable
    public String getOauth2KeysPath() {
        return oauth2KeysPath;
    }

    /**
     * Sets the path of the Athenz JWKS endpoint used for OAuth2 token verification.
     * Defaults to {@code "/oauth2/keys?rfc=true"} if not specified.
     */
    public void setOauth2KeysPath(@Nullable String oauth2KeysPath) {
        this.oauth2KeysPath = oauth2KeysPath;
    }

    /**
     * Returns the list of domains to fetch Athenz policies for.
     */
    public List<String> getDomains() {
        return domains;
    }

    /**
     * Sets the list of domains to fetch Athenz policies for.
     *
     * <p>Mandatory: This field must be set to use Athenz.
     */
    public void setDomains(List<String> domains) {
        requireNonNull(domains, "domains");
        if (domains.isEmpty()) {
            throw new IllegalArgumentException("domains must not be empty");
        }
        this.domains = domains;
    }

    /**
     * Returns whether JWS policy support is enabled.
     * The default is {@code true}.
     */
    public boolean isJwsPolicySupport() {
        return jwsPolicySupport;
    }

    /**
     * Sets whether JWS policy support is enabled.
     * The default is {@code true}.
     */
    public void setJwsPolicySupport(boolean jwsPolicySupport) {
        this.jwsPolicySupport = jwsPolicySupport;
    }

    /**
     * Returns the interval for refreshing the Athenz policy data.
     * The default is 1 hour.
     */
    public Duration getPolicyRefreshInterval() {
        return policyRefreshInterval;
    }

    /**
     * Sets the interval for refreshing the Athenz policy data.
     * The default is 1 hour.
     */
    public void setPolicyRefreshInterval(Duration policyRefreshInterval) {
        this.policyRefreshInterval = requireNonNull(policyRefreshInterval);
    }

    /**
     * Returns whether metrics collection for Athenz {@code ZtsBaseClient} is enabled.
     * The default is {@code true}.
     */
    public boolean isEnableMetrics() {
        return enableMetrics;
    }

    /**
     * Sets whether metrics collection for Athenz {@code ZtsBaseClient} is enabled.
     * The default is {@code true}.
     */
    public void setEnableMetrics(boolean enableMetrics) {
        this.enableMetrics = enableMetrics;
    }

    /**
     * Returns the meter ID prefix for Athenz {@code ZtsBaseClient} metrics.
     * The default is {@code "armeria.athenz.zts.client"}.
     */
    public String getMeterIdPrefix() {
        return meterIdPrefix;
    }

    /**
     * Sets the meter ID prefix for Athenz {@code ZtsBaseClient} metrics.
     * The default is {@code "armeria.athenz.zts.client"}.
     */
    public void setMeterIdPrefix(String meterIdPrefix) {
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("ztsUri", ztsUri)
                          .add("proxyUri", proxyUri)
                          .add("athenzPrivateKey", athenzPrivateKey)
                          .add("athenzPublicKey", athenzPublicKey)
                          .add("athenzCaCert", athenzCaCert)
                          .add("domains", domains)
                          .add("jwsPolicySupport", jwsPolicySupport)
                          .add("policyRefreshInterval", policyRefreshInterval)
                          .add("enableMetrics", enableMetrics)
                          .toString();
    }
}
