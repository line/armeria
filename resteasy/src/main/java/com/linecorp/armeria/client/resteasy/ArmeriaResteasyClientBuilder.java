/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.resteasy;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configuration;

import org.jboss.resteasy.client.jaxrs.ClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * An optional helper class to build {@link ResteasyClient} using {@link ResteasyClientBuilder} interface
 * as below.
 * <pre>{@code
 *     final WebClientBuilder webClientBuilder = WebClient.builder(); // supply no server URI to the builder
 *     // ... configure webClientBuilder ...
 *     final ResteasyClientBuilder reasteasyClientBuilder =
 *             ArmeriaResteasyClientBuilder.newBuilder(webClientBuilder);
 *     // ... configure restClientBuilder ...
 *     // construct JAX-RS client
 *     final Client jaxrsClient = reasteasyClientBuilder.build();
 *     // construct JAX-RS web target
 *     final WebTarget webTarget = jaxrsClient.target(restServerUri); // supply server URI here
 *     // make JAX-RS request
 *     final Response restResponse = webTarget.path(servicePath).request().get();
 * }</pre>
 *
 * <p>
 * {@link ResteasyClient} could still be constructed using ArmeriaJaxrsClientEngine directly by setting it
 * to {@link ResteasyClientBuilder} via {@link ResteasyClientBuilder#httpEngine(ClientHttpEngine)} method
 * as below.
 * </p>
 * <pre>{@code
 *     final Client jaxrsClient = ((ResteasyClientBuilder) ClientBuilder.newBuilder())
 *             .httpEngine(new ArmeriaJaxrsClientEngine(armeriaWebClient))
 *             .build();
 * }</pre>
 */
public final class ArmeriaResteasyClientBuilder extends ResteasyClientBuilder {

    /**
     * Creates new {@link ResteasyClientBuilder} based on {@link WebClientBuilder}
     * and {@link ClientFactoryBuilder}.
     */
    public static ResteasyClientBuilder newBuilder(WebClientBuilder webClientBuilder,
                                                   ClientFactoryBuilder clientFactoryBuilder) {
        return new ArmeriaResteasyClientBuilder(webClientBuilder, clientFactoryBuilder);
    }

    /**
     * Creates new {@link ResteasyClientBuilder} based on {@link WebClientBuilder}.
     */
    public static ResteasyClientBuilder newBuilder(WebClientBuilder webClientBuilder) {
        return new ArmeriaResteasyClientBuilder(webClientBuilder, ClientFactory.builder());
    }

    /**
     * Creates new {@link ResteasyClient} based on {@link WebClient}.
     */
    public static ResteasyClient newClient(WebClient webClient) {
        return new ArmeriaResteasyClientBuilder(WebClient.builder(), ClientFactory.builder())
                .build(webClient);
    }

    /**
     * Creates new {@link ResteasyClient} based on {@link Configuration}.
     */
    public static ResteasyClient newClient(Configuration configuration) {
        return new ArmeriaResteasyClientBuilder(WebClient.builder(), ClientFactory.builder())
                .withConfig(configuration)
                .build();
    }

    /**
     * Creates new {@link ResteasyClient} using default settings.
     */
    public static ResteasyClient newClient() {
        return new ArmeriaResteasyClientBuilder(WebClient.builder(), ClientFactory.builder())
                .build();
    }

    private final ResteasyClientBuilder delegate;
    private final WebClientBuilder webClientBuilder;
    private final ClientFactoryBuilder clientFactoryBuilder;

    ArmeriaResteasyClientBuilder(WebClientBuilder webClientBuilder, ClientFactoryBuilder clientFactoryBuilder) {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        checkArgument(clientBuilder instanceof ResteasyClientBuilder,
                      "ClientBuilder: %s (expected: ResteasyClientBuilder)",
                      clientBuilder.getClass().getName());
        delegate = (ResteasyClientBuilder) clientBuilder;
        this.webClientBuilder = requireNonNull(webClientBuilder, "webClientBuilder");
        this.clientFactoryBuilder = requireNonNull(clientFactoryBuilder, "clientFactoryBuilder");
    }

    /**
     * Configure and build new {@link WebClient} instance.
     */
    private WebClient buildWebClient() {
        // configure WebClientBuilder using ResteasyClientBuilder configuration

        // connectionTimeout -> ClientFactoryBuilder.connectTimeout
        // Value {@code 0} represents infinity. Negative values are not allowed.
        final long connectTimeout = delegate.getConnectionTimeout(TimeUnit.MILLISECONDS);
        if (connectTimeout > 0L) {
            clientFactoryBuilder.connectTimeoutMillis(connectTimeout);
        }

        // connectionTTL -> ClientFactoryBuilder.idleTimeout
        final long connectionIdleTimeout = delegate.getConnectionTTL(TimeUnit.MILLISECONDS);
        if (connectionIdleTimeout > 0L) {
            clientFactoryBuilder.idleTimeoutMillis(connectionIdleTimeout);
        }

        // defaultProxy -> ClientFactoryBuilder.proxyConfig(CONNECT)
        final String defaultProxyHost = delegate.getDefaultProxyHostname();
        if (defaultProxyHost != null) {
            final int defaultProxyPort = delegate.getDefaultProxyPort();
            final String defaultProxyScheme = delegate.getDefaultProxyScheme();
            final SessionProtocol proxyProtocol =
                    defaultProxyScheme == null ? SessionProtocol.HTTP
                                               : SessionProtocol.of(defaultProxyScheme);
            final InetSocketAddress defaultProxyAddress =
                    InetSocketAddress.createUnresolved(defaultProxyHost,
                                                       defaultProxyPort > 0 ? defaultProxyPort
                                                                            : proxyProtocol.defaultPort());
            clientFactoryBuilder.proxyConfig(ProxyConfig.connect(defaultProxyAddress, proxyProtocol.isTls()));
        }

        // trustManagerDisabled -> ClientFactoryBuilder.tlsNoVerify
        if (delegate.isTrustManagerDisabled()) {
            clientFactoryBuilder.tlsNoVerify();
        }
        // trustStore, keyStore -> ClientFactoryBuilder.tlsCustomizer
        final KeyStore trustStore = delegate.getTrustStore();
        final KeyStore keyStore = delegate.getKeyStore();
        if (trustStore != null || keyStore != null) {
            clientFactoryBuilder.tlsCustomizer(sslContextBuilder ->
                                                       SslContextConfigurator.configureSslContext(
                                                               sslContextBuilder,
                                                               keyStore,
                                                               delegate.getKeyStorePassword(),
                                                               trustStore,
                                                               delegate.isTrustSelfSignedCertificates())
            );
        }

        // readTimeout -> WebClientBuilder.responseTimeout
        // The value is the timeout to read a response. Value {@code 0} represents infinity.
        // Negative values are not allowed.
        final long readTimeout = delegate.getReadTimeout(TimeUnit.MILLISECONDS);
        if (readTimeout >= 0L) {
            webClientBuilder.responseTimeoutMillis(readTimeout);
        }

        webClientBuilder.factory(clientFactoryBuilder.build());
        return webClientBuilder.build();
    }

    private ResteasyClient build(WebClient webClient) {
        final long readTimeout = delegate.getReadTimeout(TimeUnit.MILLISECONDS);
        final int bufferSize = delegate.getResponseBufferSize();
        // responseBufferSize -> ArmeriaJaxrsClientEngine.bufferSize
        // readTimeout -> ArmeriaJaxrsClientEngine.readTimeout
        final ClientHttpEngine armeriaEngine = new ArmeriaJaxrsClientEngine(
                webClient,  bufferSize > 0 ? bufferSize : ArmeriaJaxrsClientEngine.DEFAULT_BUFFER_SIZE,
                readTimeout > 0 ? Duration.ofMillis(readTimeout) : null);
        return delegate.httpEngine(armeriaEngine).build();
    }

    @Override
    public ResteasyClient build() {
        return build(buildWebClient());
    }

    /**
     * {@link ArmeriaJaxrsClientEngine} will always be set as an {@link ClientHttpEngine}.
     * @throws UnsupportedOperationException always
     */
    @Override
    public ArmeriaResteasyClientBuilder httpEngine(ClientHttpEngine httpEngine) {
        // we'll always use an embedded ArmeriaJaxrsClientEngine
        throw new UnsupportedOperationException();
    }

    @Override
    public ClientHttpEngine getHttpEngine() {
        return delegate.getHttpEngine();
    }

    @Override
    public ArmeriaResteasyClientBuilder useAsyncHttpEngine() {
        // do nothing as ArmeriaJaxrsClientEngine is asynchronous
        return this;
    }

    @Override
    public boolean isUseAsyncHttpEngine() {
        return true; // always return TRUE as ArmeriaJaxrsClientEngine is asynchronous
    }

    @Override
    public ArmeriaResteasyClientBuilder providerFactory(ResteasyProviderFactory providerFactory) {
        delegate.providerFactory(providerFactory);
        return this;
    }

    @Override
    public ResteasyProviderFactory getProviderFactory() {
        return delegate.getProviderFactory();
    }

    @Override
    public ArmeriaResteasyClientBuilder connectionTTL(long ttl, TimeUnit unit) {
        delegate.connectionTTL(ttl, unit);
        return this;
    }

    @Override
    public long getConnectionTTL(TimeUnit unit) {
        return delegate.getConnectionTTL(unit);
    }

    @Override
    public ArmeriaResteasyClientBuilder maxPooledPerRoute(int maxPooledPerRoute) {
        delegate.maxPooledPerRoute(maxPooledPerRoute);
        return this;
    }

    @Override
    public int getMaxPooledPerRoute() {
        return delegate.getMaxPooledPerRoute();
    }

    @Override
    public ArmeriaResteasyClientBuilder connectionCheckoutTimeout(long timeout, TimeUnit unit) {
        delegate.connectionCheckoutTimeout(timeout, unit);
        return this;
    }

    @Override
    public long getConnectionCheckoutTimeout(TimeUnit unit) {
        return delegate.getConnectionCheckoutTimeout(unit);
    }

    @Override
    public ArmeriaResteasyClientBuilder connectionPoolSize(int connectionPoolSize) {
        delegate.connectionPoolSize(connectionPoolSize);
        return this;
    }

    @Override
    public int getConnectionPoolSize() {
        return delegate.getConnectionPoolSize();
    }

    @Override
    public ArmeriaResteasyClientBuilder responseBufferSize(int size) {
        delegate.responseBufferSize(size);
        return this;
    }

    @Override
    public int getResponseBufferSize() {
        return delegate.getResponseBufferSize();
    }

    @Override
    public ArmeriaResteasyClientBuilder disableTrustManager() {
        delegate.disableTrustManager();
        return this;
    }

    @Override
    public boolean isTrustManagerDisabled() {
        return delegate.isTrustManagerDisabled();
    }

    @Override
    public void setIsTrustSelfSignedCertificates(boolean b) {
        delegate.setIsTrustSelfSignedCertificates(b);
    }

    @Override
    public boolean isTrustSelfSignedCertificates() {
        return delegate.isTrustSelfSignedCertificates();
    }

    @Override
    public ArmeriaResteasyClientBuilder hostnameVerification(HostnameVerificationPolicy policy) {
        delegate.hostnameVerification(policy);
        return this;
    }

    @Override
    public HostnameVerificationPolicy getHostnameVerification() {
        return delegate.getHostnameVerification();
    }

    @Override
    public ArmeriaResteasyClientBuilder sniHostNames(String... sniHostNames) {
        delegate.sniHostNames(sniHostNames);
        return this;
    }

    @Override
    public List<String> getSniHostNames() {
        return delegate.getSniHostNames();
    }

    @Override
    public String getDefaultProxyHostname() {
        return delegate.getDefaultProxyHostname();
    }

    @Override
    public int getDefaultProxyPort() {
        return delegate.getDefaultProxyPort();
    }

    @Override
    public String getDefaultProxyScheme() {
        return delegate.getDefaultProxyScheme();
    }

    @Override
    public ArmeriaResteasyClientBuilder defaultProxy(String hostname) {
        delegate.defaultProxy(hostname);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder defaultProxy(String hostname, int port) {
        delegate.defaultProxy(hostname, port);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder defaultProxy(String hostname, int port, String scheme) {
        delegate.defaultProxy(hostname, port, scheme);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder enableCookieManagement() {
        delegate.enableCookieManagement();
        return this;
    }

    @Override
    public boolean isCookieManagementEnabled() {
        return delegate.isCookieManagementEnabled();
    }

    @Override
    public SSLContext getSSLContext() {
        return delegate.getSSLContext();
    }

    @Override
    public KeyStore getKeyStore() {
        return delegate.getKeyStore();
    }

    @Override
    public String getKeyStorePassword() {
        return delegate.getKeyStorePassword();
    }

    @Override
    public KeyStore getTrustStore() {
        return delegate.getTrustStore();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        return delegate.getHostnameVerifier();
    }

    @Override
    public long getReadTimeout(TimeUnit unit) {
        return delegate.getReadTimeout(unit);
    }

    @Override
    public long getConnectionTimeout(TimeUnit unit) {
        return delegate.getConnectionTimeout(unit);
    }

    @Override
    public ArmeriaResteasyClientBuilder disableAutomaticRetries() {
        delegate.disableAutomaticRetries();
        return this;
    }

    @Override
    public boolean isDisableAutomaticRetries() {
        return delegate.isDisableAutomaticRetries();
    }

    @Override
    public ArmeriaResteasyClientBuilder withConfig(Configuration config) {
        delegate.withConfig(config);
        return this;
    }

    /**
     * Armeria does not allow to access the ssl-context from WebClient API.
     * This functionality must be achieved by configuring {@link com.linecorp.armeria.client.WebClientBuilder}.
     * @throws UnsupportedOperationException always
     */
    @Override
    public ArmeriaResteasyClientBuilder sslContext(SSLContext sslContext) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ArmeriaResteasyClientBuilder keyStore(KeyStore keyStore, char[] password) {
        delegate.keyStore(keyStore, password);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder keyStore(KeyStore keyStore, String password) {
        delegate.keyStore(keyStore, password);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder trustStore(KeyStore trustStore) {
        delegate.trustStore(trustStore);
        return this;
    }

    /**
     * Armeria does not allow to access the HostnameVerifier from WebClient API.
     * This functionality must be achieved by configuring {@link com.linecorp.armeria.client.WebClientBuilder}.
     * @throws UnsupportedOperationException always
     */
    @Override
    public ArmeriaResteasyClientBuilder hostnameVerifier(HostnameVerifier verifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ArmeriaResteasyClientBuilder executorService(ExecutorService executorService) {
        delegate.executorService(executorService);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder executorService(
            ExecutorService executorService, boolean cleanupExecutor) {
        delegate.executorService(executorService, cleanupExecutor);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder scheduledExecutorService(
            ScheduledExecutorService scheduledExecutorService) {
        delegate.scheduledExecutorService(scheduledExecutorService);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        delegate.connectTimeout(timeout, unit);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder readTimeout(long timeout, TimeUnit unit) {
        delegate.readTimeout(timeout, unit);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder setFollowRedirects(boolean followRedirects) {
        delegate.setFollowRedirects(followRedirects);
        return this;
    }

    @Override
    public boolean isFollowRedirects() {
        return delegate.isFollowRedirects();
    }

    @Override
    public Configuration getConfiguration() {
        return delegate.getConfiguration();
    }

    @Override
    public ArmeriaResteasyClientBuilder property(String name, Object value) {
        delegate.property(name, value);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Class<?> componentClass) {
        delegate.register(componentClass);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Class<?> componentClass, int priority) {
        delegate.register(componentClass, priority);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Class<?> componentClass, Class<?>... contracts) {
        delegate.register(componentClass, contracts);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        delegate.register(componentClass, contracts);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Object component) {
        delegate.register(component);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Object component, int priority) {
        delegate.register(component, priority);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Object component, Class<?>... contracts) {
        delegate.register(component, contracts);
        return this;
    }

    @Override
    public ArmeriaResteasyClientBuilder register(Object component, Map<Class<?>, Integer> contracts) {
        delegate.register(component, contracts);
        return this;
    }
}
