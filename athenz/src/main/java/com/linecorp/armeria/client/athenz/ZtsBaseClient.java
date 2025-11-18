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
import java.net.URL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.util.AbstractListenable;
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
public final class ZtsBaseClient implements SafeCloseable {

    /**
     * Returns a new {@link ZtsBaseClientBuilder} for the specified ZTS URI.
     */
    public static ZtsBaseClientBuilder builder(String ztsUri) {
        requireNonNull(ztsUri, "ztsUri");
        return builder(URI.create(ztsUri));
    }

    /**
     * Returns a new {@link ZtsBaseClientBuilder} for the specified ZTS {@link URI}.
     */
    public static ZtsBaseClientBuilder builder(URI ztsUri) {
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

    private final TlsKeyPairListener tlsKeyPairListener = new TlsKeyPairListener();
    private final URI ztsUri;
    @Nullable
    private final URI proxyUri;
    private final ClientFactory clientFactory;
    @Nullable
    private final Consumer<WebClientBuilder> webClientConfigurer;
    private final WebClient defaultWebClient;

    ZtsBaseClient(URI ztsUri, @Nullable URI proxyUri, Supplier<TlsKeyPair> keyPairSupplier,
                  List<X509Certificate> trustedCertificates, int autoKeyRefreshIntervalMillis,
                  @Nullable Consumer<ClientFactoryBuilder> clientFactoryConfigurer,
                  @Nullable Consumer<WebClientBuilder> webClientConfigurer) {

        this.ztsUri = ztsUri;
        this.proxyUri = proxyUri;
        final TlsProvider tlsProvider =
                TlsProvider.ofScheduled(keyPairSupplier,
                                        trustedCertificates,
                                        tlsKeyPairListener::onTlsKeyPairUpdated,
                                        Duration.ofMillis(autoKeyRefreshIntervalMillis),
                                        CommonPools.blockingTaskExecutor());

        final ClientFactoryBuilder factoryBuilder = ClientFactory.builder().tlsProvider(tlsProvider);
        if (clientFactoryConfigurer != null) {
            clientFactoryConfigurer.accept(factoryBuilder);
        }
        clientFactory = factoryBuilder.build();
        this.webClientConfigurer = webClientConfigurer;
        defaultWebClient = webClient(builder -> {});
    }

    /**
     * Returns the ZTS {@link URL} that this client connects to.
     */
    public URI ztsUri() {
        return ztsUri;
    }

    /**
     * Returns the proxy {@link URL} if it was configured, or {@code null} if no proxy is set.
     */
    @Nullable
    public URI proxyUri() {
        return proxyUri;
    }

    /**
     * Returns the {@link ClientFactory} that is used to create the {@link WebClient} instances.
     */
    public ClientFactory clientFactory() {
        return clientFactory;
    }

    /**
     * Returns the {@link WebClient} that can connect to the ZTS server.
     */
    public WebClient webClient() {
        return defaultWebClient;
    }

    /**
     * Returns a new {@link WebClient} that can connect to the ZTS server with the specified configurer.
     */
    public WebClient webClient(Consumer<? super WebClientBuilder> configurer) {
        requireNonNull(configurer, "configurer");

        final WebClientBuilder clientBuilder =
                WebClient.builder(ztsUri)
                         .decorator(RetryingClient.newDecorator(RetryRule.failsafe()));

        clientBuilder.factory(clientFactory);

        if (LoggerFactory.getLogger(ZtsBaseClient.class).isTraceEnabled()) {
            final LogWriter logWriter = LogWriter.builder()
                                                 .logger(ZtsBaseClient.class.getName())
                                                 .requestLogLevel(LogLevel.TRACE)
                                                 .successfulResponseLogLevel(LogLevel.TRACE)
                                                 .build();
            clientBuilder.decorator(LoggingClient.builder()
                                                 .logWriter(logWriter)
                                                 .newDecorator());
        }
        if (webClientConfigurer != null) {
            webClientConfigurer.accept(clientBuilder);
        }
        configurer.accept(clientBuilder);
        return clientBuilder.build();
    }

    /**
     * Adds a listener that will be notified when the {@link TlsKeyPair} is updated.
     */
    public void addTlsKeyPairListener(Consumer<TlsKeyPair> listener) {
        requireNonNull(listener, "listener");
        tlsKeyPairListener.addListener(listener);
    }

    /**
     * Removes a listener that was previously added with {@link #addTlsKeyPairListener(Consumer)}.
     */
    public void removeTlsKeyPairListener(Consumer<TlsKeyPair> listener) {
        requireNonNull(listener, "listener");
        tlsKeyPairListener.removeListener(listener);
    }

    @Override
    public void close() {
        defaultWebClient.options().factory().closeAsync();
    }

    private static final class TlsKeyPairListener extends AbstractListenable<TlsKeyPair> {
        void onTlsKeyPairUpdated(TlsKeyPair newTlsKeyPair) {
            notifyListeners(newTlsKeyPair);
        }
    }
}
