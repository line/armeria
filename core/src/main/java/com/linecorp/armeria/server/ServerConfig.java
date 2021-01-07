/*
 * Copyright 2015 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Mapping;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

/**
 * {@link Server} configuration.
 */
public final class ServerConfig {

    /**
     * Initialized later by {@link Server} via {@link #setServer(Server)}.
     */
    @Nullable
    private Server server;

    private final List<ServerPort> ports;
    private final VirtualHost defaultVirtualHost;
    private final List<VirtualHost> virtualHosts;
    private final Mapping<String, VirtualHost> virtualHostMapping;
    private final List<ServiceConfig> services;

    private final EventLoopGroup workerGroup;
    private final boolean shutdownWorkerGroupOnStop;
    private final Executor startStopExecutor;
    private final int maxNumConnections;

    private final long idleTimeoutMillis;
    private final long pingIntervalMillis;
    private final long maxConnectionAgeMillis;
    private final int maxNumRequests;

    private final int http2InitialConnectionWindowSize;
    private final int http2InitialStreamWindowSize;
    private final long http2MaxStreamsPerConnection;
    private final int http2MaxFrameSize;
    private final long http2MaxHeaderListSize;
    private final int http1MaxInitialLineLength;
    private final int http1MaxHeaderSize;
    private final int http1MaxChunkSize;

    private final Duration gracefulShutdownQuietPeriod;
    private final Duration gracefulShutdownTimeout;

    private final ScheduledExecutorService blockingTaskExecutor;
    private final boolean shutdownBlockingTaskExecutorOnStop;

    private final MeterRegistry meterRegistry;

    private final int proxyProtocolMaxTlvSize;

    private final Map<ChannelOption<?>, ?> channelOptions;
    private final Map<ChannelOption<?>, ?> childChannelOptions;

    private final List<ClientAddressSource> clientAddressSources;
    private final Predicate<? super InetAddress> clientAddressTrustedProxyFilter;
    private final Predicate<? super InetAddress> clientAddressFilter;
    private final Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper;
    private final boolean enableServerHeader;
    private final boolean enableDateHeader;
    private final Supplier<RequestId> requestIdGenerator;

    @Nullable
    private String strVal;

    ServerConfig(
            Iterable<ServerPort> ports,
            VirtualHost defaultVirtualHost, Iterable<VirtualHost> virtualHosts,
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnStop, Executor startStopExecutor,
            int maxNumConnections, long idleTimeoutMillis, long pingIntervalMillis, long maxConnectionAgeMillis,
            int maxNumRequests, int http2InitialConnectionWindowSize, int http2InitialStreamWindowSize,
            long http2MaxStreamsPerConnection, int http2MaxFrameSize,
            long http2MaxHeaderListSize, int http1MaxInitialLineLength, int http1MaxHeaderSize,
            int http1MaxChunkSize, Duration gracefulShutdownQuietPeriod, Duration gracefulShutdownTimeout,
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownBlockingTaskExecutorOnStop,
            MeterRegistry meterRegistry, int proxyProtocolMaxTlvSize,
            Map<ChannelOption<?>, Object> channelOptions,
            Map<ChannelOption<?>, Object> childChannelOptions,
            List<ClientAddressSource> clientAddressSources,
            Predicate<? super InetAddress> clientAddressTrustedProxyFilter,
            Predicate<? super InetAddress> clientAddressFilter,
            Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper,
            boolean enableServerHeader, boolean enableDateHeader,
            Supplier<? extends RequestId> requestIdGenerator) {

        requireNonNull(ports, "ports");
        requireNonNull(defaultVirtualHost, "defaultVirtualHost");
        requireNonNull(virtualHosts, "virtualHosts");

        // Set the primitive properties.
        this.workerGroup = requireNonNull(workerGroup, "workerGroup");
        this.shutdownWorkerGroupOnStop = shutdownWorkerGroupOnStop;
        this.startStopExecutor = requireNonNull(startStopExecutor, "startStopExecutor");
        this.maxNumConnections = validateMaxNumConnections(maxNumConnections);
        this.idleTimeoutMillis = validateIdleTimeoutMillis(idleTimeoutMillis);
        this.pingIntervalMillis = validateNonNegative(pingIntervalMillis, "pingIntervalMillis");
        this.maxNumRequests = validateNonNegative(maxNumRequests, "maxNumRequests");
        this.maxConnectionAgeMillis = maxConnectionAgeMillis;
        this.http2InitialConnectionWindowSize = http2InitialConnectionWindowSize;
        this.http2InitialStreamWindowSize = http2InitialStreamWindowSize;
        this.http2MaxStreamsPerConnection = http2MaxStreamsPerConnection;
        this.http2MaxFrameSize = http2MaxFrameSize;
        this.http2MaxHeaderListSize = http2MaxHeaderListSize;
        this.http1MaxInitialLineLength = validateNonNegative(
                http1MaxInitialLineLength, "http1MaxInitialLineLength");
        this.http1MaxHeaderSize = validateNonNegative(
                http1MaxHeaderSize, "http1MaxHeaderSize");
        this.http1MaxChunkSize = validateNonNegative(
                http1MaxChunkSize, "http1MaxChunkSize");
        this.gracefulShutdownQuietPeriod = validateNonNegative(requireNonNull(
                gracefulShutdownQuietPeriod), "gracefulShutdownQuietPeriod");
        this.gracefulShutdownTimeout = validateNonNegative(requireNonNull(
                gracefulShutdownTimeout), "gracefulShutdownTimeout");
        validateGreaterThanOrEqual(gracefulShutdownTimeout, "gracefulShutdownTimeout",
                                   gracefulShutdownQuietPeriod, "gracefulShutdownQuietPeriod");

        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        blockingTaskExecutor =
                ExecutorServiceMetrics.monitor(meterRegistry, blockingTaskExecutor,
                                               "blockingTaskExecutor", "armeria");
        this.blockingTaskExecutor = UnstoppableScheduledExecutorService.from(blockingTaskExecutor);
        this.shutdownBlockingTaskExecutorOnStop = shutdownBlockingTaskExecutorOnStop;

        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        this.channelOptions = Collections.unmodifiableMap(
                new Object2ObjectArrayMap<>(requireNonNull(channelOptions, "channelOptions")));
        this.childChannelOptions = Collections.unmodifiableMap(
                new Object2ObjectArrayMap<>(requireNonNull(childChannelOptions, "childChannelOptions")));
        this.clientAddressSources = ImmutableList.copyOf(
                requireNonNull(clientAddressSources, "clientAddressSources"));
        this.clientAddressTrustedProxyFilter =
                requireNonNull(clientAddressTrustedProxyFilter, "clientAddressTrustedProxyFilter");
        this.clientAddressFilter = requireNonNull(clientAddressFilter, "clientAddressFilter");
        this.clientAddressMapper = requireNonNull(clientAddressMapper, "clientAddressMapper");

        // Set localAddresses.
        final List<ServerPort> portsCopy = new ArrayList<>();
        for (ServerPort p : ports) {
            if (p == null) {
                break;
            }
            portsCopy.add(p);
        }

        if (portsCopy.isEmpty()) {
            throw new IllegalArgumentException("no ports in the server");
        }

        this.ports = Collections.unmodifiableList(portsCopy);

        if (this.ports.stream().anyMatch(ServerPort::hasProxyProtocol)) {
            this.proxyProtocolMaxTlvSize = proxyProtocolMaxTlvSize;
        } else {
            this.proxyProtocolMaxTlvSize = 0;
        }

        // Set virtual host definitions and initialize their domain name mapping.
        final DomainMappingBuilder<VirtualHost> mappingBuilder =
                new DomainMappingBuilder<>(defaultVirtualHost);
        final List<VirtualHost> virtualHostsCopy = new ArrayList<>();
        for (VirtualHost h : virtualHosts) {
            if (h == null) {
                break;
            }
            virtualHostsCopy.add(h);
            mappingBuilder.add(h.hostnamePattern(), h);
        }
        virtualHostMapping = mappingBuilder.build();

        // Add the default VirtualHost to the virtualHosts so that a user can retrieve all VirtualHosts
        // via virtualHosts(). i.e. no need to check defaultVirtualHost().
        virtualHostsCopy.add(defaultVirtualHost);

        // Sets the parent of VirtualHost to this configuration.
        virtualHostsCopy.forEach(h -> h.setServerConfig(this));

        if (virtualHostsCopy.stream().allMatch(h -> h.serviceConfigs().isEmpty())) {
            throw new IllegalArgumentException("no services in the server");
        }

        this.virtualHosts = Collections.unmodifiableList(virtualHostsCopy);
        this.defaultVirtualHost = defaultVirtualHost;

        // Build the complete list of the services available in this server.
        services = virtualHostsCopy.stream()
                                   .flatMap(h -> h.serviceConfigs().stream())
                                   .collect(toImmutableList());

        this.enableServerHeader = enableServerHeader;
        this.enableDateHeader = enableDateHeader;

        @SuppressWarnings("unchecked")
        final Supplier<RequestId> castRequestIdGenerator =
                (Supplier<RequestId>) requireNonNull(requestIdGenerator, "requestIdGenerator");
        this.requestIdGenerator = castRequestIdGenerator;
    }

    static int validateMaxNumConnections(int maxNumConnections) {
        return ConnectionLimitingHandler.validateMaxNumConnections(maxNumConnections);
    }

    static long validateIdleTimeoutMillis(long idleTimeoutMillis) {
        if (idleTimeoutMillis < 0) {
            throw new IllegalArgumentException("idleTimeoutMillis: " + idleTimeoutMillis + " (expected: >= 0)");
        }
        return idleTimeoutMillis;
    }

    static long validateNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + ": " + value + " (expected: >= 0)");
        }
        return value;
    }

    static int validateNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + ": " + value + " (expected: >= 0)");
        }
        return value;
    }

    static Duration validateNonNegative(Duration duration, String fieldName) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + ": " + duration + " (expected: >= 0)");
        }
        return duration;
    }

    static void validateGreaterThanOrEqual(Duration larger, String largerFieldName,
                                           Duration smaller, String smallerFieldName) {
        if (larger.compareTo(smaller) < 0) {
            throw new IllegalArgumentException(largerFieldName + " must be greater than or equal to" +
                                               smallerFieldName);
        }
    }

    /**
     * Returns the {@link Server}.
     */
    public Server server() {
        if (server == null) {
            throw new IllegalStateException("Server has not been configured yet.");
        }

        return server;
    }

    void setServer(Server server) {
        if (this.server != null) {
            throw new IllegalStateException("ServerConfig cannot be used for more than one Server.");
        }
        this.server = requireNonNull(server, "server");
    }

    /**
     * Returns the {@link ServerPort}s to listen on.
     *
     * @see Server#activePorts()
     */
    public List<ServerPort> ports() {
        return ports;
    }

    /**
     * Returns the default {@link VirtualHost}, which is used when no other {@link VirtualHost}s match the
     * host name of a client request. e.g. the {@code "Host"} header in HTTP or host name in TLS SNI extension
     *
     * @see #virtualHosts()
     */
    public VirtualHost defaultVirtualHost() {
        return defaultVirtualHost;
    }

    /**
     * Returns the {@link List} of available {@link VirtualHost}s.
     *
     * @return the {@link List} of available {@link VirtualHost}s where its last {@link VirtualHost} is
     *         {@link #defaultVirtualHost()}
     */
    public List<VirtualHost> virtualHosts() {
        return virtualHosts;
    }

    /**
     * Finds the {@link VirtualHost} that matches the specified {@code hostname}. If there's no match, the
     * {@link #defaultVirtualHost()} is returned.
     */
    public VirtualHost findVirtualHost(String hostname) {
        return virtualHostMapping.map(hostname);
    }

    /**
     * Finds the {@link List} of {@link VirtualHost}s that contains the specified {@link HttpService}.
     * If there's no match, an empty {@link List} is returned. Note that this is potentially an expensive
     * operation and thus should not be used in a performance-sensitive path.
     */
    public List<VirtualHost> findVirtualHosts(HttpService service) {
        requireNonNull(service, "service");

        final Class<? extends HttpService> serviceType = service.getClass();
        final List<VirtualHost> res = new ArrayList<>();
        for (VirtualHost h : virtualHosts) {
            for (ServiceConfig c : h.serviceConfigs()) {
                // Consider the case where the specified service is decorated before being added.
                final HttpService unwrapped = c.service().as(serviceType);
                if (unwrapped == null) {
                    continue;
                }

                if (unwrapped == service) {
                    res.add(c.virtualHost());
                    break;
                }
            }
        }

        return res;
    }

    /**
     * Returns the information of all available {@link HttpService}s in the {@link Server}.
     */
    public List<ServiceConfig> serviceConfigs() {
        return services;
    }

    /**
     * Returns the worker {@link EventLoopGroup} which is responsible for performing socket I/O and running
     * {@link Service#serve(ServiceRequestContext, Request)}.
     */
    public EventLoopGroup workerGroup() {
        return workerGroup;
    }

    /**
     * Returns whether the worker {@link EventLoopGroup} is shut down when the {@link Server} stops.
     */
    public boolean shutdownWorkerGroupOnStop() {
        return shutdownWorkerGroupOnStop;
    }

    /**
     * Returns the {@link Executor} which will invoke the callbacks of {@link Server#start()},
     * {@link Server#stop()} and {@link ServerListener}.
     *
     * <p>Note: Kept non-public since it doesn't seem useful for users.</p>
     */
    Executor startStopExecutor() {
        return startStopExecutor;
    }

    /**
     * Returns the {@link ChannelOption}s and their values of {@link Server}'s server sockets.
     */
    public Map<ChannelOption<?>, ?> channelOptions() {
        return channelOptions;
    }

    /**
     * Returns the {@link ChannelOption}s and their values of sockets accepted by {@link Server}.
     */
    public Map<ChannelOption<?>, ?> childChannelOptions() {
        return childChannelOptions;
    }

    /**
     * Returns the maximum allowed number of open connections.
     */
    public int maxNumConnections() {
        return maxNumConnections;
    }

    /**
     * Returns the idle timeout of a connection in milliseconds for keep-alive.
     */
    public long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    /**
     * Returns the HTTP/2 PING interval in milliseconds.
     */
    public long pingIntervalMillis() {
        return pingIntervalMillis;
    }

    /**
     * Returns the maximum allowed age of a connection in milliseconds for keep-alive.
     */
    public long maxConnectionAgeMillis() {
        return maxConnectionAgeMillis;
    }

    /**
     * Returns the maximum allowed number of requests that can be served through one connection.
     */
    public int maxNumRequests() {
        return maxNumRequests;
    }

    /**
     * Returns the maximum length of an HTTP/1 response initial line.
     */
    public int http1MaxInitialLineLength() {
        return http1MaxInitialLineLength;
    }

    /**
     * Returns the maximum length of all headers in an HTTP/1 response.
     */
    public int http1MaxHeaderSize() {
        return http1MaxHeaderSize;
    }

    /**
     * Returns the maximum length of each chunk in an HTTP/1 response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     */
    public int http1MaxChunkSize() {
        return http1MaxChunkSize;
    }

    /**
     * Returns the initial connection-level HTTP/2 flow control window size.
     */
    public int http2InitialConnectionWindowSize() {
        return http2InitialConnectionWindowSize;
    }

    /**
     * Returns the initial stream-level HTTP/2 flow control window size.
     */
    public int http2InitialStreamWindowSize() {
        return http2InitialStreamWindowSize;
    }

    /**
     * Returns the maximum number of concurrent streams per HTTP/2 connection.
     */
    public long http2MaxStreamsPerConnection() {
        return http2MaxStreamsPerConnection;
    }

    /**
     * Returns the maximum size of HTTP/2 frames that can be received.
     */
    public int http2MaxFrameSize() {
        return http2MaxFrameSize;
    }

    /**
     * Returns the maximum size of headers that can be received.
     */
    public long http2MaxHeaderListSize() {
        return http2MaxHeaderListSize;
    }

    /**
     * Returns the number of milliseconds to wait for active requests to go end before shutting down.
     * {@code 0} means the server will stop right away without waiting.
     */
    public Duration gracefulShutdownQuietPeriod() {
        return gracefulShutdownQuietPeriod;
    }

    /**
     * Returns the number of milliseconds to wait before shutting down the server regardless of active
     * requests.
     */
    public Duration gracefulShutdownTimeout() {
        return gracefulShutdownTimeout;
    }

    /**
     * Returns the {@link ScheduledExecutorService} dedicated to the execution of blocking tasks or invocations.
     * Note that the {@link ScheduledExecutorService} returned by this method does not set the
     * {@link ServiceRequestContext} when executing a submitted task.
     * Use {@link ServiceRequestContext#blockingTaskExecutor()} if possible.
     */
    public ScheduledExecutorService blockingTaskExecutor() {
        return blockingTaskExecutor;
    }

    /**
     * Returns whether the worker {@link Executor} is shut down when the {@link Server} stops.
     */
    public boolean shutdownBlockingTaskExecutorOnStop() {
        return shutdownBlockingTaskExecutorOnStop;
    }

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * Returns the maximum size of additional data (TLV, Tag-Length-Value). It is only used when
     * PROXY protocol is enabled on the server port.
     */
    public int proxyProtocolMaxTlvSize() {
        return proxyProtocolMaxTlvSize;
    }

    /**
     * Returns a list of {@link ClientAddressSource}s which are used to determine where to look for
     * the client address, in the order of preference.
     */
    public List<ClientAddressSource> clientAddressSources() {
        return clientAddressSources;
    }

    /**
     * Returns a filter which evaluates whether an {@link InetAddress} of a remote endpoint is trusted.
     */
    public Predicate<? super InetAddress> clientAddressTrustedProxyFilter() {
        return clientAddressTrustedProxyFilter;
    }

    /**
     * Returns a filter which evaluates whether an {@link InetAddress} can be used as a client address.
     */
    public Predicate<? super InetAddress> clientAddressFilter() {
        return clientAddressFilter;
    }

    /**
     * Returns a {@link Function} to use when determining the client address from {@link ProxiedAddresses}.
     */
    public Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper() {
        return clientAddressMapper;
    }

    /**
     * Returns whether the response header will include default {@code "Server"} header.
     */
    public boolean isServerHeaderEnabled() {
        return enableServerHeader;
    }

    /**
     * Returns whether the response header will include default {@code "Date"} header.
     */
    public boolean isDateHeaderEnabled() {
        return enableDateHeader;
    }

    /**
     * Returns the {@link Supplier} that generates a {@link RequestId} for each {@link Request}.
     */
    public Supplier<RequestId> requestIdGenerator() {
        return requestIdGenerator;
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal == null) {
            this.strVal = strVal = toString(
                    getClass(), ports(), null, virtualHosts(),
                    workerGroup(), shutdownWorkerGroupOnStop(),
                    maxNumConnections(), idleTimeoutMillis(),
                    http2InitialConnectionWindowSize(), http2InitialStreamWindowSize(),
                    http2MaxStreamsPerConnection(), http2MaxFrameSize(), http2MaxHeaderListSize(),
                    http1MaxInitialLineLength(), http1MaxHeaderSize(), http1MaxChunkSize(),
                    proxyProtocolMaxTlvSize(), gracefulShutdownQuietPeriod(), gracefulShutdownTimeout(),
                    blockingTaskExecutor(), shutdownBlockingTaskExecutorOnStop(),
                    meterRegistry(), channelOptions(), childChannelOptions(),
                    clientAddressSources(), clientAddressTrustedProxyFilter(), clientAddressFilter(),
                    clientAddressMapper(),
                    isServerHeaderEnabled(), isDateHeaderEnabled());
        }

        return strVal;
    }

    static String toString(
            @Nullable Class<?> type, Iterable<ServerPort> ports,
            @Nullable VirtualHost defaultVirtualHost, List<VirtualHost> virtualHosts,
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnStop,
            int maxNumConnections, long idleTimeoutMillis, int http2InitialConnectionWindowSize,
            int http2InitialStreamWindowSize, long http2MaxStreamsPerConnection, int http2MaxFrameSize,
            long http2MaxHeaderListSize, long http1MaxInitialLineLength, long http1MaxHeaderSize,
            long http1MaxChunkSize, int proxyProtocolMaxTlvSize,
            Duration gracefulShutdownQuietPeriod, Duration gracefulShutdownTimeout,
            ScheduledExecutorService blockingTaskExecutor, boolean shutdownBlockingTaskExecutorOnStop,
            @Nullable MeterRegistry meterRegistry,
            Map<ChannelOption<?>, ?> channelOptions, Map<ChannelOption<?>, ?> childChannelOptions,
            List<ClientAddressSource> clientAddressSources,
            Predicate<? super InetAddress> clientAddressTrustedProxyFilter,
            Predicate<? super InetAddress> clientAddressFilter,
            Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper,
            boolean serverHeaderEnabled, boolean dateHeaderEnabled) {

        final StringBuilder buf = new StringBuilder();
        if (type != null) {
            buf.append(type.getSimpleName());
        }

        buf.append("(ports: [");

        boolean hasPorts = false;
        for (final ServerPort p : ports) {
            buf.append(ServerPort.toString(null, p.localAddress(), p.protocols()));
            buf.append(", ");
            hasPorts = true;
        }

        if (hasPorts) {
            buf.setCharAt(buf.length() - 2, ']');
            buf.setCharAt(buf.length() - 1, ',');
        } else {
            buf.append("],");
        }

        buf.append(" virtualHosts: [");
        if (!virtualHosts.isEmpty()) {
            virtualHosts.forEach(virtualHost -> {
                buf.append(virtualHost.toStringWithoutTypeName());
                buf.append(", ");
            });

            if (defaultVirtualHost != null) {
                buf.append(defaultVirtualHost.toStringWithoutTypeName());
            } else {
                buf.setLength(buf.length() - 2);
            }
        } else if (defaultVirtualHost != null) {
            buf.append(defaultVirtualHost.toStringWithoutTypeName());
        }

        buf.append("], workerGroup: ");
        buf.append(workerGroup);
        buf.append(" (shutdownOnStop=");
        buf.append(shutdownWorkerGroupOnStop);
        buf.append("), maxNumConnections: ");
        buf.append(maxNumConnections);
        buf.append(", idleTimeout: ");
        buf.append(idleTimeoutMillis);
        buf.append("ms, http2InitialConnectionWindowSize: ");
        buf.append(http2InitialConnectionWindowSize);
        buf.append("B, http2InitialStreamWindowSize: ");
        buf.append(http2InitialStreamWindowSize);
        buf.append("B, http2MaxStreamsPerConnection: ");
        buf.append(http2MaxStreamsPerConnection);
        buf.append(", http2MaxFrameSize: ");
        buf.append(http2MaxFrameSize);
        buf.append("B, http2MaxHeaderListSize: ");
        buf.append(http2MaxHeaderListSize);
        buf.append("B, http1MaxInitialLineLength: ");
        buf.append(http1MaxInitialLineLength);
        buf.append("B, http1MaxHeaderSize: ");
        buf.append(http1MaxHeaderSize);
        buf.append("B, http1MaxChunkSize: ");
        buf.append(http1MaxChunkSize);
        buf.append("B, proxyProtocolMaxTlvSize: ");
        buf.append(proxyProtocolMaxTlvSize);
        buf.append("B, gracefulShutdownQuietPeriod: ");
        buf.append(gracefulShutdownQuietPeriod);
        buf.append(", gracefulShutdownTimeout: ");
        buf.append(gracefulShutdownTimeout);
        buf.append(", blockingTaskExecutor: ");
        buf.append(blockingTaskExecutor);
        buf.append(", shutdownBlockingTaskExecutorOnStop: ");
        buf.append(shutdownBlockingTaskExecutorOnStop);
        if (meterRegistry != null) {
            buf.append(", meterRegistry: ");
            buf.append(meterRegistry);
        }
        buf.append(", channelOptions: ");
        buf.append(channelOptions);
        buf.append(", childChannelOptions: ");
        buf.append(childChannelOptions);
        buf.append(", clientAddressSources: ");
        buf.append(clientAddressSources);
        buf.append(", clientAddressTrustedProxyFilter: ");
        buf.append(clientAddressTrustedProxyFilter);
        buf.append(", clientAddressFilter: ");
        buf.append(clientAddressFilter);
        buf.append(", clientAddressMapper: ");
        buf.append(clientAddressMapper);
        buf.append(", serverHeader: ");
        buf.append(serverHeaderEnabled ? "enabled" : "disabled");
        buf.append(", dateHeader: ");
        buf.append(dateHeaderEnabled ? "enabled" : "disabled");
        buf.append(')');

        return buf.toString();
    }
}
