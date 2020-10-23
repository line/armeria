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
package com.linecorp.armeria.server.eureka;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.eureka.EurekaClientUtil.retryingClientOptions;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.AbstractWebClientBuilder;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.eureka.EurekaWebClient;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;

/**
 * Builds a {@link EurekaUpdatingListener}, which registers the server to Eureka.
 * <h2>Examples</h2>
 * <pre>{@code
 * EurekaUpdatingListener listener =
 *         EurekaUpdatingListener.builder("eureka.com:8001/eureka/v2")
 *                               .instanceId("i-00000000")
 *                               .setHostname("armeria.service.1")
 *                               .ipAddr("192.168.10.10")
 *                               .vipAddress("armeria.service.com:8080");
 *                               .build();
 * ServerBuilder sb = Server.builder();
 * sb.serverListener(listener);
 * }</pre>
 */
public final class EurekaUpdatingListenerBuilder extends AbstractWebClientBuilder {

    static final int DEFAULT_LEASE_RENEWAL_INTERVAL_SECONDS = 30;
    static final int DEFAULT_LEASE_DURATION_SECONDS = 90;
    static final String DEFAULT_DATA_CENTER_NAME = "MyOwn";

    private final InstanceInfoBuilder instanceInfoBuilder;

    /**
     * Creates a new instance.
     */
    EurekaUpdatingListenerBuilder(URI eurekaUri) {
        super(requireNonNull(eurekaUri, "eurekaUri"));
        instanceInfoBuilder = new InstanceInfoBuilder();
    }

    /**
     * Creates a new instance.
     */
    EurekaUpdatingListenerBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                                  @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
        instanceInfoBuilder = new InstanceInfoBuilder();
    }

    /**
     * Sets the interval between renewal. {@value DEFAULT_LEASE_RENEWAL_INTERVAL_SECONDS} seconds is used
     * by default and it's not recommended to modify this value. Eureka protocol stores this value in seconds
     * internally, and thus this method will convert the given interval into seconds, rounding up
     * its sub-second part. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    public EurekaUpdatingListenerBuilder renewalInterval(Duration renewalInterval) {
        requireNonNull(renewalInterval, "renewalInterval");
        checkArgument(!renewalInterval.isZero() &&
                      !renewalInterval.isNegative(),
                      "renewalInterval: %s (expected: > 0)",
                      renewalInterval);

        final int renewalIntervalSeconds = Ints.saturatedCast(
                renewalInterval.getSeconds() +
                (renewalInterval.getNano() != 0 ? 1 : 0));

        instanceInfoBuilder.renewalIntervalSeconds(renewalIntervalSeconds);
        return this;
    }

    /**
     * Sets the interval between renewal in seconds. {@value DEFAULT_LEASE_RENEWAL_INTERVAL_SECONDS} is used
     * by default and it's not recommended to modify this value. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     *
     * @deprecated Use {@link #renewalIntervalMillis(long)}.
     */
    @Deprecated
    public EurekaUpdatingListenerBuilder renewalIntervalSeconds(int renewalIntervalSeconds) {
        instanceInfoBuilder.renewalIntervalSeconds(renewalIntervalSeconds);
        return this;
    }

    /**
     * Sets the interval between renewal in milliseconds. {@code 30000} (30 seconds) is used by default and
     * it's not recommended to modify this value. Eureka protocol stores this value in seconds internally,
     * and thus this method will convert the given interval into seconds, rounding up its sub-second part. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    public EurekaUpdatingListenerBuilder renewalIntervalMillis(long renewalIntervalMillis) {
        checkArgument(renewalIntervalMillis > 0,
                      "renewalIntervalMillis: %s (expected: > 0)",
                      renewalIntervalMillis);

        final int renewalIntervalSeconds = Ints.saturatedCast(
                TimeUnit.MILLISECONDS.toSeconds(renewalIntervalMillis) +
                (renewalIntervalMillis % 1000 != 0 ? 1 : 0));

        instanceInfoBuilder.renewalIntervalSeconds(renewalIntervalSeconds);
        return this;
    }

    /**
     * Sets the lease duration. {@value DEFAULT_LEASE_DURATION_SECONDS} seconds is used by default and it's
     * not recommended to modify this value. Eureka protocol stores this value in seconds internally, and thus
     * this method will convert the given duration into seconds, rounding up its sub-second part. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    public EurekaUpdatingListenerBuilder leaseDuration(Duration leaseDuration) {
        requireNonNull(leaseDuration, "leaseDuration");
        checkArgument(!leaseDuration.isZero() &&
                      !leaseDuration.isNegative(),
                      "renewalInterval: %s (expected: > 0)",
                      leaseDuration);

        final int leaseDurationSeconds = Ints.saturatedCast(
                leaseDuration.getSeconds() +
                (leaseDuration.getNano() != 0 ? 1 : 0));

        instanceInfoBuilder.leaseDurationSeconds(leaseDurationSeconds);
        return this;
    }

    /**
     * Sets the lease duration in seconds. {@value DEFAULT_LEASE_DURATION_SECONDS} is used by default and it's
     * not recommended to modify this value. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     *
     * @deprecated Use {@link #leaseDurationMillis(long)}.
     */
    @Deprecated
    public EurekaUpdatingListenerBuilder leaseDurationSeconds(int leaseDurationSeconds) {
        instanceInfoBuilder.leaseDurationSeconds(leaseDurationSeconds);
        return this;
    }

    /**
     * Sets the lease duration in milliseconds. {@code 90000} (90 seconds) is used by default and it's not
     * recommended to modify this value. Eureka protocol stores this value in seconds internally, and thus
     * this method will convert the given duration into seconds, rounding up its sub-second part. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    public EurekaUpdatingListenerBuilder leaseDurationMillis(long leaseDurationMillis) {
        checkArgument(leaseDurationMillis > 0,
                      "leaseDurationMillis: %s (expected: > 0)",
                      leaseDurationMillis);

        final int leaseDurationSeconds = Ints.saturatedCast(
                TimeUnit.MILLISECONDS.toSeconds(leaseDurationMillis) +
                (leaseDurationMillis % 1000 != 0 ? 1 : 0));

        instanceInfoBuilder.leaseDurationSeconds(leaseDurationSeconds);
        return this;
    }

    /**
     * Sets the hostname. {@link Server#defaultHostname()} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder hostname(String hostname) {
        instanceInfoBuilder.hostname(hostname);
        return this;
    }

    /**
     * Sets the ID of this instance. {@link #hostname(String)} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder instanceId(String instanceId) {
        instanceInfoBuilder.instanceId(instanceId);
        return this;
    }

    /**
     * Sets the name of the application. {@link #hostname(String)} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder appName(String appName) {
        instanceInfoBuilder.appName(appName);
        return this;
    }

    /**
     * Sets the group name of the application.
     */
    public EurekaUpdatingListenerBuilder appGroupName(String appGroupName) {
        instanceInfoBuilder.appGroupName(appGroupName);
        return this;
    }

    /**
     * Sets the IP address. {@link SystemInfo#defaultNonLoopbackIpV4Address()} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder ipAddr(String ipAddr) {
        instanceInfoBuilder.ipAddr(ipAddr);
        return this;
    }

    /**
     * Sets the port used for {@link SessionProtocol#HTTP}.
     */
    public EurekaUpdatingListenerBuilder port(int port) {
        instanceInfoBuilder.port(port);
        return this;
    }

    /**
     * Sets the port used for {@link SessionProtocol#HTTPS}.
     */
    public EurekaUpdatingListenerBuilder securePort(int securePort) {
        instanceInfoBuilder.securePort(securePort);
        return this;
    }

    /**
     * Sets the VIP address. {@link #hostname(String)} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder vipAddress(String vipAddress) {
        instanceInfoBuilder.vipAddress(vipAddress);
        return this;
    }

    /**
     * Sets the secure VIP address. {@link #hostname(String)} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder secureVipAddress(String secureVipAddress) {
        instanceInfoBuilder.secureVipAddress(secureVipAddress);
        return this;
    }

    /**
     * Sets the home page URL.
     */
    public EurekaUpdatingListenerBuilder homePageUrl(String homePageUrl) {
        instanceInfoBuilder.homePageUrl(homePageUrl);
        return this;
    }

    /**
     * Sets the status page URL.
     */
    public EurekaUpdatingListenerBuilder statusPageUrl(String statusPageUrl) {
        instanceInfoBuilder.statusPageUrl(statusPageUrl);
        return this;
    }

    /**
     * Sets the health check URL. If {@link HealthCheckService} is added to {@link ServerBuilder} and
     * {@linkplain Server#activePort(SessionProtocol) Server.activePort(SessionProtocol.HTTP)} returns
     * an active port, then this URL will be automatically create using the information of the
     * {@link HealthCheckService}.
     */
    public EurekaUpdatingListenerBuilder healthCheckUrl(String healthCheckUrl) {
        instanceInfoBuilder.healthCheckUrl(healthCheckUrl);
        return this;
    }

    /**
     * Sets the secure health check URL. If {@link HealthCheckService} is added to {@link ServerBuilder} and
     * {@linkplain Server#activePort(SessionProtocol) Server.activePort(SessionProtocol.HTTPS)} returns
     * an active port, then this URL will be automatically create using the information of the
     * {@link HealthCheckService}.
     */
    public EurekaUpdatingListenerBuilder secureHealthCheckUrl(String secureHealthCheckUrl) {
        instanceInfoBuilder.secureHealthCheckUrl(secureHealthCheckUrl);
        return this;
    }

    /**
     * Sets the metadata.
     */
    public EurekaUpdatingListenerBuilder metadata(Map<String, String> metadata) {
        instanceInfoBuilder.metadata(metadata);
        return this;
    }

    /**
     * Sets the name of the data center.
     */
    public EurekaUpdatingListenerBuilder dataCenterName(String dataCenterName) {
        instanceInfoBuilder.dataCenterName(dataCenterName);
        return this;
    }

    /**
     * Sets the metadata of the data center.
     */
    public EurekaUpdatingListenerBuilder dataCenterMetadata(Map<String, String> dataCenterMetadata) {
        instanceInfoBuilder.dataCenterMetadata(dataCenterMetadata);
        return this;
    }

    /**
     * Returns a newly-created {@link EurekaUpdatingListener} based on the properties of this builder.
     * Note that if {@link RetryingClient} was not set using {@link #decorator(DecoratingHttpClientFunction)},
     * {@link RetryingClient} is applied automatically using
     * {@linkplain RetryingClient#newDecorator(RetryRule, int)
     * RetryingClient.newDecorator(RetryRule.failsafe(), 3)}.
     */
    public EurekaUpdatingListener build() {
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
        return new EurekaUpdatingListener(new EurekaWebClient(client), instanceInfoBuilder.build());
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public EurekaUpdatingListenerBuilder options(ClientOptions options) {
        return (EurekaUpdatingListenerBuilder) super.options(options);
    }

    @Override
    public EurekaUpdatingListenerBuilder options(ClientOptionValue<?>... options) {
        return (EurekaUpdatingListenerBuilder) super.options(options);
    }

    @Override
    public EurekaUpdatingListenerBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (EurekaUpdatingListenerBuilder) super.options(options);
    }

    @Override
    public <T> EurekaUpdatingListenerBuilder option(ClientOption<T> option, T value) {
        return (EurekaUpdatingListenerBuilder) super.option(option, value);
    }

    @Override
    public <T> EurekaUpdatingListenerBuilder option(ClientOptionValue<T> optionValue) {
        return (EurekaUpdatingListenerBuilder) super.option(optionValue);
    }

    @Override
    public EurekaUpdatingListenerBuilder factory(ClientFactory factory) {
        return (EurekaUpdatingListenerBuilder) super.factory(factory);
    }

    @Override
    public EurekaUpdatingListenerBuilder writeTimeout(Duration writeTimeout) {
        return (EurekaUpdatingListenerBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public EurekaUpdatingListenerBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (EurekaUpdatingListenerBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public EurekaUpdatingListenerBuilder responseTimeout(Duration responseTimeout) {
        return (EurekaUpdatingListenerBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public EurekaUpdatingListenerBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (EurekaUpdatingListenerBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public EurekaUpdatingListenerBuilder maxResponseLength(long maxResponseLength) {
        return (EurekaUpdatingListenerBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public EurekaUpdatingListenerBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (EurekaUpdatingListenerBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public EurekaUpdatingListenerBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (EurekaUpdatingListenerBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public EurekaUpdatingListenerBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (EurekaUpdatingListenerBuilder) super.decorator(decorator);
    }

    @Override
    public EurekaUpdatingListenerBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (EurekaUpdatingListenerBuilder) super.decorator(decorator);
    }

    @Override
    public EurekaUpdatingListenerBuilder rpcDecorator(
            Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (EurekaUpdatingListenerBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public EurekaUpdatingListenerBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (EurekaUpdatingListenerBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public EurekaUpdatingListenerBuilder clearDecorators() {
        return (EurekaUpdatingListenerBuilder) super.clearDecorators();
    }

    @Override
    public EurekaUpdatingListenerBuilder addHeader(CharSequence name, Object value) {
        return (EurekaUpdatingListenerBuilder) super.addHeader(name, value);
    }

    @Override
    public EurekaUpdatingListenerBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (EurekaUpdatingListenerBuilder) super.addHeaders(headers);
    }

    @Override
    public EurekaUpdatingListenerBuilder setHeader(CharSequence name, Object value) {
        return (EurekaUpdatingListenerBuilder) super.setHeader(name, value);
    }

    @Override
    public EurekaUpdatingListenerBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (EurekaUpdatingListenerBuilder) super.setHeaders(headers);
    }

    @Override
    public EurekaUpdatingListenerBuilder auth(BasicToken token) {
        return (EurekaUpdatingListenerBuilder) super.auth(token);
    }

    @Override
    public EurekaUpdatingListenerBuilder auth(OAuth1aToken token) {
        return (EurekaUpdatingListenerBuilder) super.auth(token);
    }

    @Override
    public EurekaUpdatingListenerBuilder auth(OAuth2Token token) {
        return (EurekaUpdatingListenerBuilder) super.auth(token);
    }
}
