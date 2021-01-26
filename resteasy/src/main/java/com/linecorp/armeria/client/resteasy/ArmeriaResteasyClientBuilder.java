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
 * <pre>{@code
 *     final Client jaxrsClient = ((ResteasyClientBuilder) ClientBuilder.newBuilder())
 *             .httpEngine(new ArmeriaJaxrsClientEngine(armeriaWebClient))
 *             .build();
 * }</pre>
 * </p>
 */
public final class ArmeriaResteasyClientBuilder extends ResteasyClientBuilder {

    /**
     * Creates new {@link ResteasyClientBuilder} based on {@link WebClientBuilder}.
     */
    public static ResteasyClientBuilder newBuilder(WebClientBuilder webClientBuilder) {
        return new ArmeriaResteasyClientBuilder(webClientBuilder);
    }

    /**
     * Creates new {@link ResteasyClient} based on {@link WebClient}.
     */
    public static ResteasyClient newClient(WebClient webClient) {
        final WebClientBuilder webClientBuilder = WebClient.builder();
        return new ArmeriaResteasyClientBuilder(webClientBuilder).build(webClient);
    }

    /**
     * Creates new {@link ResteasyClient} based on {@link Configuration}.
     */
    public static ResteasyClient newClient(Configuration configuration) {
        final WebClientBuilder webClientBuilder = WebClient.builder();
        return new ArmeriaResteasyClientBuilder(webClientBuilder).withConfig(configuration).build();
    }

    /**
     * Creates new {@link ResteasyClient} using default settings.
     */
    public static ResteasyClient newClient() {
        final WebClientBuilder webClientBuilder = WebClient.builder();
        return new ArmeriaResteasyClientBuilder(webClientBuilder).build(webClientBuilder.build());
    }

    private final ResteasyClientBuilder delegate;
    private final WebClientBuilder webClientBuilder;

    ArmeriaResteasyClientBuilder(WebClientBuilder webClientBuilder) {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        checkArgument(clientBuilder instanceof ResteasyClientBuilder,
                      "ClientBuilder: %s (expected: ResteasyClientBuilder)",
                      clientBuilder.getClass().getName());
        delegate = (ResteasyClientBuilder) clientBuilder;
        this.webClientBuilder = requireNonNull(webClientBuilder, "webClientBuilder");
    }

    /**
     * Configure and build new {@link WebClient} instance.
     */
    private WebClient buildWebClient() {
        // configure WebClientBuilder using ResteasyClientBuilder configuration
        final ClientFactoryBuilder clientFactoryBuilder = ClientFactory.builder();

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
    public ResteasyClientBuilder httpEngine(ClientHttpEngine httpEngine) {
        // we'll always use an embedded ArmeriaJaxrsClientEngine
        throw new UnsupportedOperationException();
    }

    @Override
    public ClientHttpEngine getHttpEngine() {
        return delegate.getHttpEngine();
    }

    @Override
    public ResteasyClientBuilder useAsyncHttpEngine() {
        // do nothing as ArmeriaJaxrsClientEngine is asynchronous
        return delegate;
    }

    @Override
    public boolean isUseAsyncHttpEngine() {
        return true; // always return TRUE as ArmeriaJaxrsClientEngine is asynchronous
    }

    @Override
    public ResteasyClientBuilder providerFactory(ResteasyProviderFactory providerFactory) {
        delegate.providerFactory(providerFactory);
        return delegate;
    }

    @Override
    public ResteasyProviderFactory getProviderFactory() {
        return delegate.getProviderFactory();
    }

    @Override
    public ResteasyClientBuilder connectionTTL(long ttl, TimeUnit unit) {
        delegate.connectionTTL(ttl, unit);
        return delegate;
    }

    @Override
    public long getConnectionTTL(TimeUnit unit) {
        return delegate.getConnectionTTL(unit);
    }

    @Override
    public ResteasyClientBuilder maxPooledPerRoute(int maxPooledPerRoute) {
        delegate.maxPooledPerRoute(maxPooledPerRoute);
        return delegate;
    }

    @Override
    public int getMaxPooledPerRoute() {
        return delegate.getMaxPooledPerRoute();
    }

    @Override
    public ResteasyClientBuilder connectionCheckoutTimeout(long timeout, TimeUnit unit) {
        delegate.connectionCheckoutTimeout(timeout, unit);
        return delegate;
    }

    @Override
    public long getConnectionCheckoutTimeout(TimeUnit unit) {
        return delegate.getConnectionCheckoutTimeout(unit);
    }

    @Override
    public ResteasyClientBuilder connectionPoolSize(int connectionPoolSize) {
        delegate.connectionPoolSize(connectionPoolSize);
        return delegate;
    }

    @Override
    public int getConnectionPoolSize() {
        return delegate.getConnectionPoolSize();
    }

    @Override
    public ResteasyClientBuilder responseBufferSize(int size) {
        delegate.responseBufferSize(size);
        return delegate;
    }

    @Override
    public int getResponseBufferSize() {
        return delegate.getResponseBufferSize();
    }

    @Override
    public ResteasyClientBuilder disableTrustManager() {
        delegate.disableTrustManager();
        return delegate;
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
    public ResteasyClientBuilder hostnameVerification(HostnameVerificationPolicy policy) {
        delegate.hostnameVerification(policy);
        return delegate;
    }

    @Override
    public HostnameVerificationPolicy getHostnameVerification() {
        return delegate.getHostnameVerification();
    }

    @Override
    public ResteasyClientBuilder sniHostNames(String... sniHostNames) {
        delegate.sniHostNames(sniHostNames);
        return delegate;
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
    public ResteasyClientBuilder defaultProxy(String hostname) {
        delegate.defaultProxy(hostname);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder defaultProxy(String hostname, int port) {
        delegate.defaultProxy(hostname, port);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder defaultProxy(String hostname, int port, String scheme) {
        delegate.defaultProxy(hostname, port, scheme);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder enableCookieManagement() {
        delegate.enableCookieManagement();
        return delegate;
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
    public ResteasyClientBuilder disableAutomaticRetries() {
        delegate.disableAutomaticRetries();
        return delegate;
    }

    @Override
    public boolean isDisableAutomaticRetries() {
        return delegate.isDisableAutomaticRetries();
    }

    @Override
    public ResteasyClientBuilder withConfig(Configuration config) {
        delegate.withConfig(config);
        return delegate;
    }

    /**
     * Armeria does not allow to access the ssl-context from WebClient API.
     * This functionality must be achieved by configuring {@link com.linecorp.armeria.client.WebClientBuilder}.
     * @throws UnsupportedOperationException always
     */
    @Override
    public ResteasyClientBuilder sslContext(SSLContext sslContext) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResteasyClientBuilder keyStore(KeyStore keyStore, char[] password) {
        delegate.keyStore(keyStore, password);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder keyStore(KeyStore keyStore, String password) {
        delegate.keyStore(keyStore, password);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder trustStore(KeyStore trustStore) {
        delegate.trustStore(trustStore);
        return delegate;
    }

    /**
     * Armeria does not allow to access the HostnameVerifier from WebClient API.
     * This functionality must be achieved by configuring {@link com.linecorp.armeria.client.WebClientBuilder}.
     * @throws UnsupportedOperationException always
     */
    @Override
    public ResteasyClientBuilder hostnameVerifier(HostnameVerifier verifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResteasyClientBuilder executorService(ExecutorService executorService) {
        delegate.executorService(executorService);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder executorService(ExecutorService executorService, boolean cleanupExecutor) {
        delegate.executorService(executorService, cleanupExecutor);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder scheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        delegate.scheduledExecutorService(scheduledExecutorService);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        delegate.connectTimeout(timeout, unit);
        return delegate;
    }

    @Override
    public ResteasyClientBuilder readTimeout(long timeout, TimeUnit unit) {
        delegate.readTimeout(timeout, unit);
        return delegate;
    }

    @Override
    public Configuration getConfiguration() {
        return delegate.getConfiguration();
    }

    @Override
    public ClientBuilder property(String name, Object value) {
        delegate.property(name, value);
        return delegate;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass) {
        delegate.register(componentClass);
        return delegate;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, int priority) {
        delegate.register(componentClass, priority);
        return delegate;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Class<?>... contracts) {
        delegate.register(componentClass, contracts);
        return delegate;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        delegate.register(componentClass, contracts);
        return delegate;
    }

    @Override
    public ClientBuilder register(Object component) {
        delegate.register(component);
        return delegate;
    }

    @Override
    public ClientBuilder register(Object component, int priority) {
        delegate.register(component, priority);
        return delegate;
    }

    @Override
    public ClientBuilder register(Object component, Class<?>... contracts) {
        delegate.register(component, contracts);
        return delegate;
    }

    @Override
    public ClientBuilder register(Object component, Map<Class<?>, Integer> contracts) {
        delegate.register(component, contracts);
        return delegate;
    }
}
