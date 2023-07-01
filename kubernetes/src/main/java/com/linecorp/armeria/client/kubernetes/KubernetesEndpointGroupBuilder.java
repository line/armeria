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
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroupSetters;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
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

public final class KubernetesEndpointGroupBuilder extends AbstractWebClientBuilder
        implements DynamicEndpointGroupSetters {

    private static final long DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS = 30000;
    private final DynamicEndpointGroupBuilder dynamicEndpointGroupBuilder = new DynamicEndpointGroupBuilder();
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    private String namespace = "default";

    @Nullable
    private String serviceName;

    private long registryFetchIntervalMillis = DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS;

    KubernetesEndpointGroupBuilder(URI k8sApiUri) {
        super(requireNonNull(k8sApiUri, "k8sApiUri"));
    }

    KubernetesEndpointGroupBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                                   @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
    }

    /**
     * Sets the <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/">namespace</a>
     * of a Kubernetes cluster.
     */
    public KubernetesEndpointGroupBuilder namespace(String namespace) {
        this.namespace = requireNonNull(namespace, "namespace");
        return this;
    }

    /**
     * Sets the target <a href="https://kubernetes.io/docs/concepts/services-networking/service/">service</a> name
     * from which {@link Endpoint}s should be fetched.
     */
    public KubernetesEndpointGroupBuilder serviceName(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        return this;
    }

    /**
     * Sets the access token that will be used for authentication to access Kubernetes API.
     * The token should have permissions for "pods", "nodes" and "services"
     * <a href="https://kubernetes.io/docs/reference/access-authn-authz/rbac/#referring-to-resources">resources</a>.
     */
    @Override
    public KubernetesEndpointGroupBuilder auth(AuthToken accessToken) {
        requireNonNull(accessToken, "accessToken");
        super.auth(accessToken);
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public KubernetesEndpointGroupBuilder registryFetchIntervalMillis(long registryFetchIntervalMillis) {
        checkArgument(registryFetchIntervalMillis > 0, "registryFetchIntervalSeconds: %s (expected: > 0)",
                      registryFetchIntervalMillis);
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public KubernetesEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        checkArgument(!registryFetchInterval.isZero() &&
                      !registryFetchInterval.isNegative(),
                      "registryFetchInterval: %s (expected: > 0)",
                      registryFetchInterval);
        return registryFetchIntervalMillis(registryFetchInterval.toMillis());
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link KubernetesEndpointGroupBuilder}.
     */
    public KubernetesEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    @Override
    public KubernetesEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        dynamicEndpointGroupBuilder.allowEmptyEndpoints(allowEmptyEndpoints);
        return this;
    }

    @Override
    public KubernetesEndpointGroupBuilder selectionTimeoutMillis(long selectionTimeoutMillis) {
        dynamicEndpointGroupBuilder.selectionTimeoutMillis(selectionTimeoutMillis);
        return this;
    }

    /**
     * Returns a newly-created {@link KubernetesEndpointGroup} based on the properties of this builder.
     */
    public KubernetesEndpointGroup build() {
        final WebClient webClient = buildWebClient();
        final boolean allowEmptyEndpoints = dynamicEndpointGroupBuilder.shouldAllowEmptyEndpoints();
        final long selectionTimeoutMillis = dynamicEndpointGroupBuilder.selectionTimeoutMillis();
        checkState(serviceName != null, "serviceName can't be null");

        return new KubernetesEndpointGroup(webClient, namespace, serviceName, selectionStrategy, allowEmptyEndpoints,
                                           selectionTimeoutMillis, registryFetchIntervalMillis);
    }

    // Override the return type of the following methods in the superclass.

    @Override
    public KubernetesEndpointGroupBuilder options(ClientOptions options) {
        return (KubernetesEndpointGroupBuilder) super.options(options);
    }

    @Override
    public KubernetesEndpointGroupBuilder options(ClientOptionValue<?>... options) {
        return (KubernetesEndpointGroupBuilder) super.options(options);
    }

    @Override
    public KubernetesEndpointGroupBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (KubernetesEndpointGroupBuilder) super.options(options);
    }

    @Override
    public <T> KubernetesEndpointGroupBuilder option(ClientOption<T> option, T value) {
        return (KubernetesEndpointGroupBuilder) super.option(option, value);
    }

    @Override
    public <T> KubernetesEndpointGroupBuilder option(ClientOptionValue<T> optionValue) {
        return (KubernetesEndpointGroupBuilder) super.option(optionValue);
    }

    @Override
    public KubernetesEndpointGroupBuilder factory(ClientFactory factory) {
        return (KubernetesEndpointGroupBuilder) super.factory(factory);
    }

    @Override
    public KubernetesEndpointGroupBuilder writeTimeout(Duration writeTimeout) {
        return (KubernetesEndpointGroupBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public KubernetesEndpointGroupBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (KubernetesEndpointGroupBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public KubernetesEndpointGroupBuilder responseTimeout(Duration responseTimeout) {
        return (KubernetesEndpointGroupBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public KubernetesEndpointGroupBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (KubernetesEndpointGroupBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public KubernetesEndpointGroupBuilder maxResponseLength(long maxResponseLength) {
        return (KubernetesEndpointGroupBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public KubernetesEndpointGroupBuilder requestAutoAbortDelay(Duration delay) {
        return (KubernetesEndpointGroupBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public KubernetesEndpointGroupBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (KubernetesEndpointGroupBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public KubernetesEndpointGroupBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (KubernetesEndpointGroupBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public KubernetesEndpointGroupBuilder successFunction(SuccessFunction successFunction) {
        return (KubernetesEndpointGroupBuilder) super.successFunction(successFunction);
    }

    @Override
    public KubernetesEndpointGroupBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (KubernetesEndpointGroupBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public KubernetesEndpointGroupBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (KubernetesEndpointGroupBuilder) super.decorator(decorator);
    }

    @Override
    public KubernetesEndpointGroupBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (KubernetesEndpointGroupBuilder) super.decorator(decorator);
    }

    @Override
    public KubernetesEndpointGroupBuilder clearDecorators() {
        return (KubernetesEndpointGroupBuilder) super.clearDecorators();
    }

    @Override
    public KubernetesEndpointGroupBuilder addHeader(CharSequence name, Object value) {
        return (KubernetesEndpointGroupBuilder) super.addHeader(name, value);
    }

    @Override
    public KubernetesEndpointGroupBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (KubernetesEndpointGroupBuilder) super.addHeaders(headers);
    }

    @Override
    public KubernetesEndpointGroupBuilder setHeader(CharSequence name, Object value) {
        return (KubernetesEndpointGroupBuilder) super.setHeader(name, value);
    }

    @Override
    public KubernetesEndpointGroupBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (KubernetesEndpointGroupBuilder) super.setHeaders(headers);
    }

    @Override
    public KubernetesEndpointGroupBuilder auth(BasicToken token) {
        return (KubernetesEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public KubernetesEndpointGroupBuilder auth(OAuth1aToken token) {
        return (KubernetesEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public KubernetesEndpointGroupBuilder auth(OAuth2Token token) {
        return (KubernetesEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public KubernetesEndpointGroupBuilder followRedirects() {
        return (KubernetesEndpointGroupBuilder) super.followRedirects();
    }

    @Override
    public KubernetesEndpointGroupBuilder followRedirects(RedirectConfig redirectConfig) {
        return (KubernetesEndpointGroupBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public KubernetesEndpointGroupBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (KubernetesEndpointGroupBuilder) super.contextCustomizer(contextCustomizer);
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
