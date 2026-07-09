/*
 * Copyright 2026 LY Corporation
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
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.util.AbstractListenable;

/**
 * The default {@link ZtsBaseClient} implementation that manages a {@link ClientFactory} with
 * {@link TlsKeyPair} rotation and proxy configuration.
 */
final class DefaultZtsBaseClient implements ZtsBaseClient {

    private final TlsKeyPairListener tlsKeyPairListener = new TlsKeyPairListener();
    private final URI ztsUri;
    private final ClientFactory clientFactory;
    @Nullable
    private final Consumer<WebClientBuilder> webClientConfigurer;
    private final WebClient defaultWebClient;

    DefaultZtsBaseClient(URI ztsUri, Supplier<TlsKeyPair> keyPairSupplier,
                         List<X509Certificate> trustedCertificates, int autoKeyRefreshIntervalMillis,
                         @Nullable Consumer<ClientFactoryBuilder> clientFactoryConfigurer,
                         @Nullable Consumer<WebClientBuilder> webClientConfigurer) {

        this.ztsUri = ztsUri;
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

    @Override
    public ClientFactory clientFactory() {
        return clientFactory;
    }

    @Override
    public WebClient webClient() {
        return defaultWebClient;
    }

    @Override
    public WebClient webClient(Consumer<? super WebClientBuilder> configurer) {
        requireNonNull(configurer, "configurer");

        final WebClientBuilder clientBuilder =
                WebClient.builder(ztsUri)
                         .decorator(RetryingClient.newDecorator(RetryRule.failsafe()));

        clientBuilder.factory(clientFactory);

        if (LoggerFactory.getLogger(DefaultZtsBaseClient.class).isTraceEnabled()) {
            final LogWriter logWriter = LogWriter.builder()
                                                 .logger(DefaultZtsBaseClient.class.getName())
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

    @Override
    public void addTlsKeyPairListener(Consumer<TlsKeyPair> listener) {
        requireNonNull(listener, "listener");
        tlsKeyPairListener.addListener(listener);
    }

    @Override
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
