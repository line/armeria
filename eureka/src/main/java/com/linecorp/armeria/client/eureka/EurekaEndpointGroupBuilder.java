/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.eureka;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.eureka.EurekaClientUtil.retryingClientOptions;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.AbstractWebClientBuilder;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroupSetters;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * Builds a {@link EurekaEndpointGroup}.
 */
public final class EurekaEndpointGroupBuilder extends AbstractWebClientBuilder
        implements DynamicEndpointGroupSetters<EurekaEndpointGroupBuilder> {

    private static final long DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS = 30000;

    private final DynamicEndpointGroupBuilder dynamicEndpointGroupBuilder = new DynamicEndpointGroupBuilder();
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    @Nullable
    private String appName;

    @Nullable
    private String instanceId;

    @Nullable
    private String vipAddress;

    @Nullable
    private String secureVipAddress;

    private long registryFetchIntervalMillis = DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS;

    @Nullable
    private List<String> regions;

    /**
     * Creates a new instance.
     */
    EurekaEndpointGroupBuilder(URI eurekaUri) {
        super(requireNonNull(eurekaUri, "eurekaUri"));
    }

    /**
     * Creates a new instance.
     */
    EurekaEndpointGroupBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                               @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link EurekaEndpointGroup}.
     */
    public EurekaEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Sets the specified {@code regions}. {@link EurekaEndpointGroup} will retrieve the registry information
     * which belongs to the {@code regions}.
     */
    public EurekaEndpointGroupBuilder regions(String... regions) {
        return regions(ImmutableList.copyOf(requireNonNull(regions, "regions")));
    }

    /**
     * Sets the specified {@code regions}. {@link EurekaEndpointGroup} will retrieve the registry information
     * which belongs to the {@code regions}.
     */
    public EurekaEndpointGroupBuilder regions(Iterable<String> regions) {
        this.regions = ImmutableList.copyOf(requireNonNull(regions, "regions"));
        return this;
    }

    /**
     * Sets the specified {@code appName}. {@link EurekaEndpointGroup} will retrieve the registry information
     * whose application name is the specified {@code appName}.
     *
     * @throws IllegalStateException if {@link #vipAddress(String)} or {@link #secureVipAddress(String)} is
     *                               called already
     */
    public EurekaEndpointGroupBuilder appName(String appName) {
        requireNonNull(appName, "appName");
        checkArgument(!appName.isEmpty(), "appName is empty.");
        checkState(vipAddress == null && secureVipAddress == null,
                   "cannot set appName with the %s.", vipAddress != null ? "vipAddress" : "secureVipAddress");
        this.appName = appName;
        return this;
    }

    /**
     * Sets the specified {@code instanceId}. {@link EurekaEndpointGroup} will only retrieve the registry
     * information whose instance ID is the specified {@code instanceId}.
     *
     * @throws IllegalStateException if {@link #vipAddress(String)} or {@link #secureVipAddress(String)} is
     *                               called already
     */
    public EurekaEndpointGroupBuilder instanceId(String instanceId) {
        requireNonNull(instanceId, "instanceId");
        checkArgument(!instanceId.isEmpty(), "instanceId is empty.");
        checkState(vipAddress == null && secureVipAddress == null,
                   "cannot set instanceId with the %s.",
                   vipAddress != null ? "vipAddress" : "secureVipAddress");
        this.instanceId = instanceId;
        return this;
    }

    /**
     * Sets the specified {@code vipAddress}. {@link EurekaEndpointGroup} will retrieve the registry information
     * whose VIP address is the specified {@code vipAddress}.
     *
     * @throws IllegalStateException if {@link #appName(String)}, {@link #instanceId(String)} or
     *                               {@link #secureVipAddress(String)} is called already
     */
    public EurekaEndpointGroupBuilder vipAddress(String vipAddress) {
        requireNonNull(vipAddress, "vipAddress");
        checkArgument(!vipAddress.isEmpty(), "vipAddress is empty.");
        checkState(appName == null && instanceId == null && secureVipAddress == null,
                   "cannot set vipAddress with the %s.",
                   secureVipAddress != null ? "secureVipAddress" : "appName or instanceId");
        this.vipAddress = vipAddress;
        return this;
    }

    /**
     * Sets the specified {@code secureVipAddress}. {@link EurekaEndpointGroup} will retrieve the
     * registry information whose VIP address is the specified {@code secureVipAddress}.
     *
     * @throws IllegalStateException if {@link #appName(String)}, {@link #instanceId(String)} or
     *                               {@link #vipAddress(String)} is called already
     */
    public EurekaEndpointGroupBuilder secureVipAddress(String secureVipAddress) {
        requireNonNull(secureVipAddress, "secureVipAddress");
        checkArgument(!secureVipAddress.isEmpty(), "secureVipAddress is empty.");
        checkState(appName == null && instanceId == null && vipAddress == null,
                   "cannot set secureVipAddress with the %s.",
                   vipAddress != null ? "vipAddress" : "appName or instanceId");
        this.secureVipAddress = secureVipAddress;
        return this;
    }

    /**
     * Sets the interval between fetching registry requests. {@value #DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS}
     * milliseconds is used by default and it's not recommended to modify this value. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#fetch-registry">
     * fetch-registry</a>.
     */
    public EurekaEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        checkArgument(!registryFetchInterval.isZero() &&
                      !registryFetchInterval.isNegative(),
                      "registryFetchInterval: %s (expected: > 0)",
                      registryFetchInterval);
        return registryFetchIntervalMillis(registryFetchInterval.toMillis());
    }

    /**
     * Sets the interval between fetching registry requests in seconds. {@code 30} is used by default and
     * it's not recommended to modify this value. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#fetch-registry">
     * fetch-registry</a>.
     *
     * @deprecated Use {@link #registryFetchIntervalMillis(long)}.
     */
    @Deprecated
    public EurekaEndpointGroupBuilder registryFetchIntervalSeconds(long registryFetchIntervalSeconds) {
        checkArgument(registryFetchIntervalSeconds > 0, "registryFetchIntervalSeconds: %s (expected: > 0)",
                      registryFetchIntervalSeconds);
        return registryFetchIntervalMillis(TimeUnit.SECONDS.toMillis(registryFetchIntervalSeconds));
    }

    /**
     * Sets the interval between fetching registry requests in milliseconds.
     * {@value #DEFAULT_REGISTRY_FETCH_INTERVAL_MILLIS} is used by default and it's not recommended to modify
     * this value. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#fetch-registry">
     * fetch-registry</a>.
     */
    public EurekaEndpointGroupBuilder registryFetchIntervalMillis(long registryFetchIntervalMillis) {
        checkArgument(registryFetchIntervalMillis > 0, "registryFetchIntervalMillis: %s (expected: > 0)",
                      registryFetchIntervalMillis);
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        return this;
    }

    /**
     * Returns a newly-created {@link EurekaEndpointGroup} based on the properties set so far. Note that
     * if {@link RetryingClient} was not set using {@link #decorator(DecoratingHttpClientFunction)},
     * {@link RetryingClient} is applied automatically using
     * {@linkplain RetryingClient#newDecorator(RetryRule, int)
     * RetryingClient.newDecorator(RetryRule.failsafe(), 3)}.
     */
    public EurekaEndpointGroup build() {
        final WebClient webClient = buildWebClient();
        final WebClient client;
        if (webClient.as(RetryingClient.class) != null) {
            client = webClient;
        } else {
            final ClientOptions options = buildOptions(retryingClientOptions());
            final ClientBuilderParams params = clientBuilderParams(options);
            final ClientFactory factory = options.factory();
            client = (WebClient) factory.newClient(params);
        }
        final boolean allowEmptyEndpoints = dynamicEndpointGroupBuilder.shouldAllowEmptyEndpoints();
        final long selectionTimeoutMillis = dynamicEndpointGroupBuilder.selectionTimeoutMillis();
        return new EurekaEndpointGroup(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis,
                                       client, registryFetchIntervalMillis, appName,
                                       instanceId, vipAddress, secureVipAddress, regions);
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public EurekaEndpointGroupBuilder options(ClientOptions options) {
        return (EurekaEndpointGroupBuilder) super.options(options);
    }

    @Override
    public EurekaEndpointGroupBuilder options(ClientOptionValue<?>... options) {
        return (EurekaEndpointGroupBuilder) super.options(options);
    }

    @Override
    public EurekaEndpointGroupBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (EurekaEndpointGroupBuilder) super.options(options);
    }

    @Override
    public <T> EurekaEndpointGroupBuilder option(ClientOption<T> option, T value) {
        return (EurekaEndpointGroupBuilder) super.option(option, value);
    }

    @Override
    public <T> EurekaEndpointGroupBuilder option(ClientOptionValue<T> optionValue) {
        return (EurekaEndpointGroupBuilder) super.option(optionValue);
    }

    @Override
    public EurekaEndpointGroupBuilder factory(ClientFactory factory) {
        return (EurekaEndpointGroupBuilder) super.factory(factory);
    }

    @Override
    public EurekaEndpointGroupBuilder writeTimeout(Duration writeTimeout) {
        return (EurekaEndpointGroupBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public EurekaEndpointGroupBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (EurekaEndpointGroupBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public EurekaEndpointGroupBuilder responseTimeout(Duration responseTimeout) {
        return (EurekaEndpointGroupBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public EurekaEndpointGroupBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (EurekaEndpointGroupBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public EurekaEndpointGroupBuilder maxResponseLength(long maxResponseLength) {
        return (EurekaEndpointGroupBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public EurekaEndpointGroupBuilder requestAutoAbortDelay(Duration delay) {
        return (EurekaEndpointGroupBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public EurekaEndpointGroupBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (EurekaEndpointGroupBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public EurekaEndpointGroupBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (EurekaEndpointGroupBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public EurekaEndpointGroupBuilder successFunction(SuccessFunction successFunction) {
        return (EurekaEndpointGroupBuilder) super.successFunction(successFunction);
    }

    @Override
    public EurekaEndpointGroupBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (EurekaEndpointGroupBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public EurekaEndpointGroupBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (EurekaEndpointGroupBuilder) super.decorator(decorator);
    }

    @Override
    public EurekaEndpointGroupBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (EurekaEndpointGroupBuilder) super.decorator(decorator);
    }

    @Override
    public EurekaEndpointGroupBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (EurekaEndpointGroupBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public EurekaEndpointGroupBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (EurekaEndpointGroupBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public EurekaEndpointGroupBuilder clearDecorators() {
        return (EurekaEndpointGroupBuilder) super.clearDecorators();
    }

    @Override
    public EurekaEndpointGroupBuilder addHeader(CharSequence name, Object value) {
        return (EurekaEndpointGroupBuilder) super.addHeader(name, value);
    }

    @Override
    public EurekaEndpointGroupBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (EurekaEndpointGroupBuilder) super.addHeaders(headers);
    }

    @Override
    public EurekaEndpointGroupBuilder setHeader(CharSequence name, Object value) {
        return (EurekaEndpointGroupBuilder) super.setHeader(name, value);
    }

    @Override
    public EurekaEndpointGroupBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (EurekaEndpointGroupBuilder) super.setHeaders(headers);
    }

    @Override
    public EurekaEndpointGroupBuilder auth(BasicToken token) {
        return (EurekaEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public EurekaEndpointGroupBuilder auth(OAuth1aToken token) {
        return (EurekaEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public EurekaEndpointGroupBuilder auth(OAuth2Token token) {
        return (EurekaEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public EurekaEndpointGroupBuilder auth(AuthToken token) {
        return (EurekaEndpointGroupBuilder) super.auth(token);
    }

    @Override
    public EurekaEndpointGroupBuilder followRedirects() {
        return (EurekaEndpointGroupBuilder) super.followRedirects();
    }

    @Override
    public EurekaEndpointGroupBuilder followRedirects(RedirectConfig redirectConfig) {
        return (EurekaEndpointGroupBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public EurekaEndpointGroupBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (EurekaEndpointGroupBuilder) super.contextCustomizer(contextCustomizer);
    }

    @Override
    public EurekaEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        dynamicEndpointGroupBuilder.allowEmptyEndpoints(allowEmptyEndpoints);
        return this;
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@link Duration#ZERO} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by default.
     */
    @Override
    public EurekaEndpointGroupBuilder selectionTimeout(Duration selectionTimeout) {
        dynamicEndpointGroupBuilder.selectionTimeout(selectionTimeout);
        return this;
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@code 0} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by default.
     */
    @Override
    public EurekaEndpointGroupBuilder selectionTimeoutMillis(long selectionTimeoutMillis) {
        dynamicEndpointGroupBuilder.selectionTimeoutMillis(selectionTimeoutMillis);
        return this;
    }

    /**
     * This workaround delegates DynamicEndpointGroupSetters properties to AbstractDynamicEndpointGroupBuilder.
     * EurekaEndpointGroupBuilder can't extend AbstractDynamicEndpointGroupBuilder because it already extends
     * EurekaEndpointGroupBuilder.
     */
    private static class DynamicEndpointGroupBuilder
            extends AbstractDynamicEndpointGroupBuilder<DynamicEndpointGroupBuilder> {

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
