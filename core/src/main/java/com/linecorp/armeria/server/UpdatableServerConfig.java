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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;

final class UpdatableServerConfig implements ServerConfig {

    private DefaultServerConfig delegate;

    UpdatableServerConfig(DefaultServerConfig delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    void updateConfig(DefaultServerConfig newConfig) {
        delegate = requireNonNull(newConfig, "newConfig");
    }

    // Delegate non-public methods

    @Nullable
    Mapping<String, SslContext> sslContextMapping() {
        return delegate.sslContextMapping();
    }

    Executor startStopExecutor() {
        return delegate.startStopExecutor();
    }

    // Delegate public methods

    @Override
    public Server server() {
        return delegate.server();
    }

    @Override
    public List<ServerPort> ports() {
        return delegate.ports();
    }

    @Override
    public VirtualHost defaultVirtualHost() {
        return delegate.defaultVirtualHost();
    }

    @Override
    public List<VirtualHost> virtualHosts() {
        return delegate.virtualHosts();
    }

    @Override
    @Deprecated
    public VirtualHost findVirtualHost(String hostname) {
        return delegate.findVirtualHost(hostname);
    }

    @Override
    public VirtualHost findVirtualHost(String hostname, int port) {
        return delegate.findVirtualHost(hostname, port);
    }

    @Override
    public List<VirtualHost> findVirtualHosts(HttpService service) {
        return delegate.findVirtualHosts(service);
    }

    @Override
    public List<ServiceConfig> serviceConfigs() {
        return delegate.serviceConfigs();
    }

    @Override
    public EventLoopGroup workerGroup() {
        return delegate.workerGroup();
    }

    @Override
    public boolean shutdownWorkerGroupOnStop() {
        return delegate.shutdownWorkerGroupOnStop();
    }

    @Override
    public Map<ChannelOption<?>, ?> channelOptions() {
        return delegate.channelOptions();
    }

    @Override
    public Map<ChannelOption<?>, ?> childChannelOptions() {
        return delegate.childChannelOptions();
    }

    @Override
    public int maxNumConnections() {
        return delegate.maxNumConnections();
    }

    @Override
    public long idleTimeoutMillis() {
        return delegate.idleTimeoutMillis();
    }

    @Override
    public long pingIntervalMillis() {
        return delegate.pingIntervalMillis();
    }

    @Override
    public long maxConnectionAgeMillis() {
        return delegate.maxConnectionAgeMillis();
    }

    @Override
    public long connectionDrainDurationMicros() {
        return delegate.connectionDrainDurationMicros();
    }

    @Override
    public int maxNumRequestsPerConnection() {
        return delegate.maxNumRequestsPerConnection();
    }

    @Override
    public int http1MaxInitialLineLength() {
        return delegate.http1MaxInitialLineLength();
    }

    @Override
    public int http1MaxHeaderSize() {
        return delegate.http1MaxHeaderSize();
    }

    @Override
    public int http1MaxChunkSize() {
        return delegate.http1MaxChunkSize();
    }

    @Override
    public int http2InitialConnectionWindowSize() {
        return delegate.http2InitialConnectionWindowSize();
    }

    @Override
    public int http2InitialStreamWindowSize() {
        return delegate.http2InitialStreamWindowSize();
    }

    @Override
    public long http2MaxStreamsPerConnection() {
        return delegate.http2MaxStreamsPerConnection();
    }

    @Override
    public int http2MaxFrameSize() {
        return delegate.http2MaxFrameSize();
    }

    @Override
    public long http2MaxHeaderListSize() {
        return delegate.http2MaxHeaderListSize();
    }

    @Override
    public Duration gracefulShutdownQuietPeriod() {
        return delegate.gracefulShutdownQuietPeriod();
    }

    @Override
    public Duration gracefulShutdownTimeout() {
        return delegate.gracefulShutdownTimeout();
    }

    @Override
    public ScheduledExecutorService blockingTaskExecutor() {
        return delegate.blockingTaskExecutor();
    }

    @Override
    public boolean shutdownBlockingTaskExecutorOnStop() {
        return delegate.shutdownBlockingTaskExecutorOnStop();
    }

    @Override
    public MeterRegistry meterRegistry() {
        return delegate.meterRegistry();
    }

    @Override
    public int proxyProtocolMaxTlvSize() {
        return delegate.proxyProtocolMaxTlvSize();
    }

    @Override
    public List<ClientAddressSource> clientAddressSources() {
        return delegate.clientAddressSources();
    }

    @Override
    public Predicate<? super InetAddress> clientAddressTrustedProxyFilter() {
        return delegate.clientAddressTrustedProxyFilter();
    }

    @Override
    public Predicate<? super InetAddress> clientAddressFilter() {
        return delegate.clientAddressFilter();
    }

    @Override
    public Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper() {
        return delegate.clientAddressMapper();
    }

    @Override
    public boolean isDateHeaderEnabled() {
        return delegate.isDateHeaderEnabled();
    }

    @Override
    public boolean isServerHeaderEnabled() {
        return delegate.isServerHeaderEnabled();
    }

    @Override
    public Supplier<RequestId> requestIdGenerator() {
        return delegate.requestIdGenerator();
    }

    @Override
    public ServerErrorHandler errorHandler() {
        return delegate.errorHandler();
    }

    @Override
    public Http1HeaderNaming http1HeaderNaming() {
        return delegate.http1HeaderNaming();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
