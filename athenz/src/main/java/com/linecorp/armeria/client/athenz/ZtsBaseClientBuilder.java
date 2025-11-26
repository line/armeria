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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.client.proxy.ConnectProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.util.CertificateUtil;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A builder for creating a {@link ZtsBaseClient} instance.
 */
@UnstableApi
public final class ZtsBaseClientBuilder {

    private final URI ztsUri;
    @Nullable
    private URI proxyUri;

    @Nullable
    private Supplier<TlsKeyPair> athenzKeyPairSupplier;
    private int autoKeyRefreshIntervalMillis = 60000; // Default to 60 seconds
    @Nullable
    private Consumer<ClientFactoryBuilder> clientFactoryConfigurer;
    @Nullable
    private Consumer<WebClientBuilder> webClientConfigurer;
    private List<X509Certificate> trustedCertificates = ImmutableList.of();

    ZtsBaseClientBuilder(URI ztsUri) {
        this.ztsUri = ztsUri;
    }

    /**
     * Sets the Athenz private and public key files for mutual TLS authentication.
     */
    public ZtsBaseClientBuilder keyPair(String athenzPrivateKeyPath, String athenzPublicKeyPath) {
        requireNonNull(athenzPrivateKeyPath, "athenzPrivateKeyPath");
        requireNonNull(athenzPublicKeyPath, "athenzPublicKeyPath");
        return keyPair(new File(athenzPrivateKeyPath), new File(athenzPublicKeyPath));
    }

    /**
     * Sets the Athenz private and public key files for mutual TLS authentication.
     */
    public ZtsBaseClientBuilder keyPair(File athenzPrivateKeyFile, File athenzPublicKeyFile) {
        requireNonNull(athenzPrivateKeyFile, "athenzPrivateKeyFile");
        requireNonNull(athenzPublicKeyFile, "athenzPublicKeyFile");
        athenzKeyPairSupplier = () -> TlsKeyPair.of(athenzPrivateKeyFile, athenzPublicKeyFile);
        return this;
    }

    /**
     * Sets the Athenz private and public key files for mutual TLS authentication.
     */
    public ZtsBaseClientBuilder keyPair(Supplier<TlsKeyPair> athenzKeyPairSupplier) {
        requireNonNull(athenzKeyPairSupplier, "keyPairSupplier");
        this.athenzKeyPairSupplier = athenzKeyPairSupplier;
        return this;
    }

    /**
     * Sets the trusted certificate file for verifying the ZTS server's certificate.
     */
    public ZtsBaseClientBuilder trustedCertificate(String trustedCertificateFile) {
        requireNonNull(trustedCertificateFile, "trustedCertificateFile");
        return trustedCertificate(new File(trustedCertificateFile));
    }

    /**
     * Sets the trusted certificate file for verifying the ZTS server's certificate.
     */
    public ZtsBaseClientBuilder trustedCertificate(File trustedCertificateFile) {
        requireNonNull(trustedCertificateFile, "trustedCertificateFile");
        try {
            trustedCertificates = CertificateUtil.toX509Certificates(trustedCertificateFile);
        } catch (CertificateException e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    /**
     * Sets the trusted certificate input stream for verifying the ZTS server's certificate.
     */
    public ZtsBaseClientBuilder trustedCertificate(InputStream trustedCertificateInputStream) {
        requireNonNull(trustedCertificateInputStream, "trustedCertificateInputStream");
        try {
            trustedCertificates = CertificateUtil.toX509Certificates(trustedCertificateInputStream);
        } catch (CertificateException e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    /**
     * Sets the interval in milliseconds for automatically refreshing the Athenz key pair.
     * If not specified, defaults to 60 seconds.
     */
    public ZtsBaseClientBuilder autoKeyRefreshIntervalMillis(int autoKeyRefreshIntervalMillis) {
        checkArgument(autoKeyRefreshIntervalMillis > 0, "autoKeyRefreshIntervalMillis: %s (expected > 0)",
                      autoKeyRefreshIntervalMillis);
        this.autoKeyRefreshIntervalMillis = autoKeyRefreshIntervalMillis;
        return this;
    }

    /**
     * Sets the proxy URI for the ZTS client.
     */
    public ZtsBaseClientBuilder proxyUri(String proxyUrl) {
        requireNonNull(proxyUrl, "proxyUrl");
        return proxyUri(URI.create(proxyUrl));
    }

    /**
     * Sets the proxy {@link URI} for the ZTS client.
     */
    public ZtsBaseClientBuilder proxyUri(URI proxyUri) {
        requireNonNull(proxyUri, "proxyUri");
        this.proxyUri = proxyUri;
        final boolean isTls = "https".equalsIgnoreCase(proxyUri.getScheme());
        final int port = proxyUri.getPort() == -1 ? (isTls ? 443 : 80) : proxyUri.getPort();
        final InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved(proxyUri.getHost(), port);
        final ConnectProxyConfig proxyConfig = ProxyConfig.connect(proxyAddress, isTls);
        return configureClientFactory(factoryBuilder -> factoryBuilder.proxyConfig(proxyConfig));
    }

    /**
     * Configures the {@link ClientFactory} used by this client.
     */
    public ZtsBaseClientBuilder configureClientFactory(Consumer<? super ClientFactoryBuilder> configurer) {
        requireNonNull(configurer, "configurer");
        if (clientFactoryConfigurer == null) {
            //noinspection unchecked
            clientFactoryConfigurer = (Consumer<ClientFactoryBuilder>) configurer;
        } else {
            clientFactoryConfigurer = clientFactoryConfigurer.andThen(configurer);
        }
        return this;
    }

    /**
     * Configures the {@link WebClient} used by this client.
     */
    public ZtsBaseClientBuilder configureWebClient(Consumer<? super WebClientBuilder> configurer) {
        requireNonNull(configurer, "configurer");
        if (webClientConfigurer == null) {
            //noinspection unchecked
            webClientConfigurer = (Consumer<WebClientBuilder>) configurer;
        } else {
            webClientConfigurer = webClientConfigurer.andThen(configurer);
        }
        return this;
    }

    /**
     * Enable metrics collection for the ZTS client using the specified {@link MeterRegistry} and
     * {@link MeterIdPrefixFunction}.
     */
    public ZtsBaseClientBuilder enableMetrics(MeterRegistry meterRegistry,
                                              MeterIdPrefixFunction meterIdPrefixFunction) {
        requireNonNull(meterRegistry, "meterRegistry");
        requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        configureClientFactory(fb -> fb.meterRegistry(meterRegistry));
        configureWebClient(cb -> cb.decorator(MetricCollectingClient.newDecorator(meterIdPrefixFunction)));
        return this;
    }

    /**
     * Builds a new {@link ZtsBaseClient} instance with the configured settings.
     */
    public ZtsBaseClient build() {
        if (athenzKeyPairSupplier == null) {
            throw new IllegalStateException("Athenz key pair must be set");
        }
        return new ZtsBaseClient(ztsUri, proxyUri, athenzKeyPairSupplier, trustedCertificates,
                                 autoKeyRefreshIntervalMillis, clientFactoryConfigurer, webClientConfigurer);
    }
}
