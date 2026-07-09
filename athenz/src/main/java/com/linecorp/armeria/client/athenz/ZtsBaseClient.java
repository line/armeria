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

package com.linecorp.armeria.client.athenz;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.athenz.AthenzService;

/**
 * A base client for Athenz ZTS that provides common functionality such as {@link TlsKeyPair} management
 * and {@link WebClient} configuration. It is recommended to create a new instance and share it across multiple
 * {@link AthenzClient} and {@link AthenzService}.
 *
 * <p>Example:
 * <pre>{@code
 * ZtsBaseClient ztsBaseClient =
 *   ZtsBaseClient
 *     .builder("https://athenz.example.com:8443/zts/v1")
 *     .keyPair("/var/lib/athenz/service.key.pem", "/var/lib/athenz/service.cert.pem")
 *     .build();
 * }</pre>
 */
@UnstableApi
public interface ZtsBaseClient extends SafeCloseable {

    /**
     * Returns a new {@link ZtsBaseClientBuilder} for the specified ZTS URI.
     */
    static ZtsBaseClientBuilder builder(String ztsUri) {
        requireNonNull(ztsUri, "ztsUri");
        return builder(URI.create(ztsUri));
    }

    /**
     * Returns a new {@link ZtsBaseClientBuilder} for the specified ZTS {@link URI}.
     */
    static ZtsBaseClientBuilder builder(URI ztsUri) {
        requireNonNull(ztsUri, "ztsUri");
        return new ZtsBaseClientBuilder(normalizeZtsUri(ztsUri));
    }

    private static URI normalizeZtsUri(URI ztsUri) {
        // Respect the upstream behavior of ZTS, which requires the URI to end with "/zts/v1".
        String rawUri = ztsUri.toString();
        if (!rawUri.endsWith("/zts/v1")) {
            if (rawUri.charAt(rawUri.length() - 1) != '/') {
                rawUri += "/";
            }
            rawUri += "zts/v1";
        }
        return URI.create(rawUri);
    }

    /**
     * Returns the ZTS {@link URI} that this client connects to.
     *
     * @deprecated Use {@code webClient().uri()} instead.
     */
    @Deprecated
    default URI ztsUri() {
        return webClient().uri();
    }

    /**
     * Returns the proxy {@link URI} if it was configured, or {@code null} if no proxy is set.
     *
     * @deprecated The proxy is configured at the {@link ClientFactory} level via
     *             {@link ZtsBaseClientBuilder#proxyUri(URI)} and is already applied to the {@link WebClient}.
     */
    @Deprecated
    @Nullable
    default URI proxyUri() {
        return null;
    }

    /**
     * Returns the {@link ClientFactory} that is used to create the {@link WebClient} instances.
     */
    default ClientFactory clientFactory() {
        return webClient().options().factory();
    }

    /**
     * Returns the {@link WebClient} that can connect to the ZTS server.
     */
    WebClient webClient();

    /**
     * Returns a new {@link WebClient} that can connect to the ZTS server with the specified configurer.
     */
    default WebClient webClient(Consumer<? super WebClientBuilder> configurer) {
        requireNonNull(configurer, "configurer");
        return webClient();
    }

    /**
     * Adds a listener that will be notified when the {@link TlsKeyPair} is updated.
     */
    default void addTlsKeyPairListener(Consumer<TlsKeyPair> listener) {
        requireNonNull(listener, "listener");
    }

    /**
     * Removes a listener that was previously added with {@link #addTlsKeyPairListener(Consumer)}.
     */
    default void removeTlsKeyPairListener(Consumer<TlsKeyPair> listener) {
        requireNonNull(listener, "listener");
    }
}
