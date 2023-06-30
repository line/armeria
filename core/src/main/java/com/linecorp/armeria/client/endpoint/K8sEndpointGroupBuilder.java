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

package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.AbstractWebClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

public final class K8sEndpointGroupBuilder extends AbstractWebClientBuilder
        implements DynamicEndpointGroupSetters {

    private static final long DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS = 30000;
    private final DynamicEndpointGroupBuilder dynamicEndpointGroupBuilder = new DynamicEndpointGroupBuilder();
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    private String namespace = "default";

    @Nullable
    private String serviceName;

    private long registryFetchIntervalMillis = DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS;

    K8sEndpointGroupBuilder(URI k8sApiUri) {
        super(requireNonNull(k8sApiUri, "k8sApiUri"));
    }

    K8sEndpointGroupBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                            @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
    }

    /**
     * Sets the <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/">namespace</a>
     * of a Kubernetes cluster.
     */
    public K8sEndpointGroupBuilder namespace(String namespace) {
        this.namespace = requireNonNull(namespace, "namespace");
        return this;
    }

    /**
     * Sets the target <a href="https://kubernetes.io/docs/concepts/services-networking/service/">service</a> name
     * from which {@link Endpoint}s should be fetched.
     */
    public K8sEndpointGroupBuilder serviceName(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        return this;
    }

    /**
     * Sets the access token that will be used for authentication to access Kubernetes API.
     * The token should have permissions for "pods", "nodes" and "services"
     * <a href="https://kubernetes.io/docs/reference/access-authn-authz/rbac/#referring-to-resources">resources</a>.
     */
    @Override
    public K8sEndpointGroupBuilder auth(AuthToken accessToken) {
        requireNonNull(accessToken, "accessToken");
        super.auth(accessToken);
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public K8sEndpointGroupBuilder registryFetchIntervalMillis(long registryFetchIntervalMillis) {
        checkArgument(registryFetchIntervalMillis > 0, "registryFetchIntervalSeconds: %s (expected: > 0)",
                      registryFetchIntervalMillis);
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public K8sEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        checkArgument(!registryFetchInterval.isZero() &&
                      !registryFetchInterval.isNegative(),
                      "registryFetchInterval: %s (expected: > 0)",
                      registryFetchInterval);
        return registryFetchIntervalMillis(registryFetchInterval.toMillis());
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link K8sEndpointGroupBuilder}.
     */
    public K8sEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    @Override
    public K8sEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        dynamicEndpointGroupBuilder.allowEmptyEndpoints(allowEmptyEndpoints);
        return this;
    }

    @Override
    public K8sEndpointGroupBuilder selectionTimeoutMillis(long selectionTimeoutMillis) {
        dynamicEndpointGroupBuilder.selectionTimeoutMillis(selectionTimeoutMillis);
        return this;
    }

    /**
     * Returns a newly-created {@link K8sEndpointGroup} based on the properties of this builder.
     */
    public K8sEndpointGroup build() {
        final WebClient webClient = buildWebClient();
        final boolean allowEmptyEndpoints = dynamicEndpointGroupBuilder.shouldAllowEmptyEndpoints();
        final long selectionTimeoutMillis = dynamicEndpointGroupBuilder.selectionTimeoutMillis();
        checkState(serviceName != null, "serviceName can't be null");

        return new K8sEndpointGroup(webClient, namespace, serviceName, selectionStrategy, allowEmptyEndpoints,
                                    selectionTimeoutMillis, registryFetchIntervalMillis);
    }

    // Override the return type of the following methods in the superclass.

    @Override
    public K8sEndpointGroupBuilder options(ClientOptions options) {
        return (K8sEndpointGroupBuilder) super.options(options);
    }

    @Override
    public K8sEndpointGroupBuilder options(ClientOptionValue<?>... options) {
        return (K8sEndpointGroupBuilder) super.options(options);
    }

    @Override
    public K8sEndpointGroupBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (K8sEndpointGroupBuilder) super.options(options);
    }

    @Override
    public <T> K8sEndpointGroupBuilder option(ClientOption<T> option, T value) {
        return (K8sEndpointGroupBuilder) super.option(option, value);
    }

    @Override
    public <T> K8sEndpointGroupBuilder option(ClientOptionValue<T> optionValue) {
        return (K8sEndpointGroupBuilder) super.option(optionValue);
    }

    @Override
    public K8sEndpointGroupBuilder factory(ClientFactory factory) {
        return (K8sEndpointGroupBuilder) super.factory(factory);
    }

    @Override
    public K8sEndpointGroupBuilder writeTimeout(Duration writeTimeout) {
        return (K8sEndpointGroupBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public K8sEndpointGroupBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (K8sEndpointGroupBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public K8sEndpointGroupBuilder responseTimeout(Duration responseTimeout) {
        return (K8sEndpointGroupBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public K8sEndpointGroupBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (K8sEndpointGroupBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public K8sEndpointGroupBuilder maxResponseLength(long maxResponseLength) {
        return (K8sEndpointGroupBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public K8sEndpointGroupBuilder requestAutoAbortDelay(Duration delay) {
        return (K8sEndpointGroupBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public K8sEndpointGroupBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (K8sEndpointGroupBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public K8sEndpointGroupBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (K8sEndpointGroupBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public K8sEndpointGroupBuilder successFunction(SuccessFunction successFunction) {
        return (K8sEndpointGroupBuilder) super.successFunction(successFunction);
    }

    @Override
    public K8sEndpointGroupBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (K8sEndpointGroupBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public K8sEndpointGroupBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (K8sEndpointGroupBuilder) super.decorator(decorator);
    }

    @Override
    public K8sEndpointGroupBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (K8sEndpointGroupBuilder) super.decorator(decorator);
    }

    @Override
    public K8sEndpointGroupBuilder clearDecorators() {
        return (K8sEndpointGroupBuilder) super.clearDecorators();
    }

    @Override
    public K8sEndpointGroupBuilder addHeader(CharSequence name, Object value) {
        return (K8sEndpointGroupBuilder) super.addHeader(name, value);
    }

    @Override
    public K8sEndpointGroupBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (K8sEndpointGroupBuilder) super.addHeaders(headers);
    }

    @Override
    public K8sEndpointGroupBuilder setHeader(CharSequence name, Object value) {
        return (K8sEndpointGroupBuilder) super.setHeader(name, value);
    }

    @Override
    public K8sEndpointGroupBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (K8sEndpointGroupBuilder) super.setHeaders(headers);
    }

    @Override
    public K8sEndpointGroupBuilder auth(BasicToken token) {
        return (K8sEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public K8sEndpointGroupBuilder auth(OAuth1aToken token) {
        return (K8sEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public K8sEndpointGroupBuilder auth(OAuth2Token token) {
        return (K8sEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public K8sEndpointGroupBuilder followRedirects() {
        return (K8sEndpointGroupBuilder) super.followRedirects();
    }

    @Override
    public K8sEndpointGroupBuilder followRedirects(RedirectConfig redirectConfig) {
        return (K8sEndpointGroupBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public K8sEndpointGroupBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (K8sEndpointGroupBuilder) super.contextCustomizer(contextCustomizer);
    }

    private static class DynamicEndpointGroupBuilder extends AbstractDynamicEndpointGroupBuilder {

        DynamicEndpointGroupBuilder() {
            super(Flags.defaultResponseTimeoutMillis());
        }

        @Override
        public boolean shouldAllowEmptyEndpoints() {
            return super.shouldAllowEmptyEndpoints();
        }

        @Override
        public long selectionTimeoutMillis() {
            return super.selectionTimeoutMillis();
        }
    }
}
