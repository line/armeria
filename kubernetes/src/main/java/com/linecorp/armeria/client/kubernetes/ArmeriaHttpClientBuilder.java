/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.stream.Stream;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.LogLevel;

import io.fabric8.kubernetes.client.http.StandardHttpClientBuilder;
import io.fabric8.kubernetes.client.http.TlsVersion;

/**
 * A {@link StandardHttpClientBuilder} to build an {@link ArmeriaHttpClient}.
 */
public final class ArmeriaHttpClientBuilder extends StandardHttpClientBuilder<
        ArmeriaHttpClient, ArmeriaHttpClientFactory, ArmeriaHttpClientBuilder> {

    ArmeriaHttpClientBuilder(ArmeriaHttpClientFactory clientFactory) {
        super(clientFactory);
    }

    @Override
    public ArmeriaHttpClient build() {
        if (client != null) {
            return new ArmeriaHttpClient(this, client.getWebClient());
        }

        final WebClientBuilder clientBuilder = WebClient.builder();
        clientBuilder.factory(clientFactory(false));

        if (LoggerFactory.getLogger(ArmeriaHttpClient.class).isTraceEnabled()) {
            clientBuilder.decorator(LoggingClient.builder()
                                                 .requestLogLevel(LogLevel.TRACE)
                                                 .logger(ArmeriaHttpClient.class.getName())
                                                 .successfulResponseLogLevel(LogLevel.TRACE)
                                                 .newDecorator());
            // 16 KiB should be enough for most of the cases.
            clientBuilder.decorator(ContentPreviewingClient.newDecorator(16 * 1024));
        }

        if (followRedirects) {
            clientBuilder.followRedirects();
        }

        final WebClient webClient = clientBuilder.build();
        return client = new ArmeriaHttpClient(this, webClient);
    }

    ClientFactory clientFactory(boolean webSocket) {
        final ClientFactoryBuilderHolder factoryBuilderHolder = new ClientFactoryBuilderHolder();
        if (connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative()) {
            factoryBuilderHolder.get().connectTimeout(connectTimeout);
        }

        // Kubernetes WebSocket does not support HTTP/2.
        if (isPreferHttp11() || webSocket) {
            factoryBuilderHolder.get().preferHttp1(true);
        }

        if (sslContext != null) {
            final KeyManager keyManager =
                    (keyManagers != null && keyManagers.length > 0) ? keyManagers[0] : null;
            final TrustManager trustManager =
                    (trustManagers != null && trustManagers.length > 0) ? trustManagers[0] : null;

            // TODO(ikhoon): Use TlsProvider when https://github.com/line/armeria/pull/5228 is merged.
            factoryBuilderHolder.get().tlsCustomizer(sslContextBuilder -> {
                if (keyManager != null) {
                    sslContextBuilder.keyManager(keyManager);
                }
                if (trustManager != null) {
                    sslContextBuilder.trustManager(trustManager);
                }
            });
        }

        if (tlsVersions != null && tlsVersions.length > 0) {
            factoryBuilderHolder.get().tlsCustomizer(sslContextBuilder -> {
                sslContextBuilder.protocols(Stream.of(tlsVersions)
                                                  .map(TlsVersion::javaName)
                                                  .collect(toImmutableList()));
            });
        }

        ProxyConfig proxyConfig = ProxyConfig.direct();
        if (proxyAddress != null) {
            switch (proxyType) {
                case HTTP:
                    if (proxyAuthorization != null) {
                        final HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.PROXY_AUTHORIZATION,
                                                                   proxyAuthorization);
                        proxyConfig = ProxyConfig.connect(proxyAddress, headers, false);
                    } else {
                        proxyConfig = ProxyConfig.connect(proxyAddress);
                    }
                    break;
                case SOCKS4:
                    proxyConfig = ProxyConfig.socks4(proxyAddress);
                    break;
                case SOCKS5:
                    proxyConfig = ProxyConfig.socks5(proxyAddress);
                    break;
            }
        }
        factoryBuilderHolder.get().proxyConfig(proxyConfig);

        return factoryBuilderHolder.maybeBuild();
    }

    @Override
    protected ArmeriaHttpClientBuilder newInstance(ArmeriaHttpClientFactory clientFactory) {
        return new ArmeriaHttpClientBuilder(clientFactory);
    }

    private static final class ClientFactoryBuilderHolder {

        @Nullable
        private ClientFactoryBuilder factoryBuilder;

        ClientFactoryBuilder get() {
            if (factoryBuilder == null) {
                return factoryBuilder = ClientFactory.builder();
            }
            return factoryBuilder;
        }

        ClientFactory maybeBuild() {
            if (factoryBuilder != null) {
                return factoryBuilder.build();
            } else {
                return ClientFactory.ofDefault();
            }
        }
    }
}
