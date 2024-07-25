/*
 * Copyright 2022 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

final class DefaultServerConfig implements ServerConfig {

    /**
     * Initialized later by {@link Server} via {@link #setServer(Server)}.
     */
    @Nullable
    private Server server;

    private final List<ServerPort> ports;
    private final VirtualHost defaultVirtualHost;
    private final List<VirtualHost> virtualHosts;
    @Nullable
    private final Int2ObjectMap<Mapping<String, VirtualHost>> virtualHostAndPortMapping;
    private final List<ServiceConfig> services;

    private final EventLoopGroup workerGroup;
    private final boolean shutdownWorkerGroupOnStop;
    private final Executor startStopExecutor;
    private final int maxNumConnections;

    private final long idleTimeoutMillis;
    private final boolean keepAliveOnPing;
    private final long pingIntervalMillis;
    private final long maxConnectionAgeMillis;
    private final long connectionDrainDurationMicros;
    private final int maxNumRequestsPerConnection;

    private final int http2InitialConnectionWindowSize;
    private final int http2InitialStreamWindowSize;
    private final long http2MaxStreamsPerConnection;
    private final int http2MaxFrameSize;
    private final long http2MaxHeaderListSize;
    private final int http2MaxResetFramesPerWindow;
    private final int http2MaxResetFramesWindowSeconds;
    private final int http1MaxInitialLineLength;
    private final int http1MaxHeaderSize;
    private final int http1MaxChunkSize;

    private final Duration gracefulShutdownQuietPeriod;
    private final Duration gracefulShutdownTimeout;

    private final BlockingTaskExecutor blockingTaskExecutor;

    private final MeterRegistry meterRegistry;

    private final int proxyProtocolMaxTlvSize;

    private final Map<ChannelOption<?>, ?> channelOptions;
    private final Map<ChannelOption<?>, ?> childChannelOptions;
    private final Consumer<? super ChannelPipeline> childChannelPipelineCustomizer;

    private final List<ClientAddressSource> clientAddressSources;
    private final Predicate<? super InetAddress> clientAddressTrustedProxyFilter;
    private final Predicate<? super InetAddress> clientAddressFilter;
    private final Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper;
    private final boolean enableServerHeader;
    private final boolean enableDateHeader;
    private final ServerErrorHandler errorHandler;
    private final Http1HeaderNaming http1HeaderNaming;
    private final DependencyInjector dependencyInjector;
    private final Function<String, String> absoluteUriTransformer;
    private final long unloggedExceptionsReportIntervalMillis;
    private final List<ShutdownSupport> shutdownSupports;

    @Nullable
    private final Mapping<String, SslContext> sslContexts;
    private final ServerMetrics serverMetrics = new ServerMetrics();

    @Nullable
    private String strVal;

    DefaultServerConfig(
            Iterable<ServerPort> ports,
            VirtualHost defaultVirtualHost, List<VirtualHost> virtualHosts,
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnStop, Executor startStopExecutor,
            int maxNumConnections, long idleTimeoutMillis, boolean keepAliveOnPing, long pingIntervalMillis,
            long maxConnectionAgeMillis,
            int maxNumRequestsPerConnection, long connectionDrainDurationMicros,
            int http2InitialConnectionWindowSize, int http2InitialStreamWindowSize,
            long http2MaxStreamsPerConnection, int http2MaxFrameSize, long http2MaxHeaderListSize,
            int http2MaxResetFramesPerWindow, int http2MaxResetFramesWindowSeconds,
            int http1MaxInitialLineLength, int http1MaxHeaderSize,
            int http1MaxChunkSize, Duration gracefulShutdownQuietPeriod, Duration gracefulShutdownTimeout,
            BlockingTaskExecutor blockingTaskExecutor,
            MeterRegistry meterRegistry, int proxyProtocolMaxTlvSize,
            Map<ChannelOption<?>, Object> channelOptions,
            Map<ChannelOption<?>, Object> childChannelOptions,
            Consumer<? super ChannelPipeline> childChannelPipelineCustomizer,
            List<ClientAddressSource> clientAddressSources,
            Predicate<? super InetAddress> clientAddressTrustedProxyFilter,
            Predicate<? super InetAddress> clientAddressFilter,
            Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper,
            boolean enableServerHeader, boolean enableDateHeader,
            ServerErrorHandler errorHandler,
            @Nullable Mapping<String, SslContext> sslContexts,
            Http1HeaderNaming http1HeaderNaming,
            DependencyInjector dependencyInjector,
            Function<? super String, String> absoluteUriTransformer,
            long unloggedExceptionsReportIntervalMillis,
            List<ShutdownSupport> shutdownSupports) {
        requireNonNull(ports, "ports");
        requireNonNull(defaultVirtualHost, "defaultVirtualHost");
        requireNonNull(virtualHosts, "virtualHosts");

        // Set the primitive properties.
        this.workerGroup = requireNonNull(workerGroup, "workerGroup");
        this.shutdownWorkerGroupOnStop = shutdownWorkerGroupOnStop;
        this.startStopExecutor = requireNonNull(startStopExecutor, "startStopExecutor");
        this.maxNumConnections = validateMaxNumConnections(maxNumConnections);
        this.idleTimeoutMillis = validateIdleTimeoutMillis(idleTimeoutMillis);
        this.keepAliveOnPing = keepAliveOnPing;
        this.pingIntervalMillis = validateNonNegative(pingIntervalMillis, "pingIntervalMillis");
        this.maxNumRequestsPerConnection =
                validateNonNegative(maxNumRequestsPerConnection, "maxNumRequestsPerConnection");
        this.maxConnectionAgeMillis = maxConnectionAgeMillis;
        this.connectionDrainDurationMicros = validateNonNegative(connectionDrainDurationMicros,
                                                                 "connectionDrainDurationMicros");
        this.http2InitialConnectionWindowSize = http2InitialConnectionWindowSize;
        this.http2InitialStreamWindowSize = http2InitialStreamWindowSize;
        this.http2MaxStreamsPerConnection = http2MaxStreamsPerConnection;
        this.http2MaxFrameSize = http2MaxFrameSize;
        this.http2MaxHeaderListSize = http2MaxHeaderListSize;
        this.http2MaxResetFramesPerWindow = http2MaxResetFramesPerWindow;
        this.http2MaxResetFramesWindowSeconds = http2MaxResetFramesWindowSeconds;
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
        this.blockingTaskExecutor = monitorBlockingTaskExecutor(blockingTaskExecutor, meterRegistry);

        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        this.channelOptions = Collections.unmodifiableMap(
                new Object2ObjectArrayMap<>(requireNonNull(channelOptions, "channelOptions")));
        this.childChannelOptions = Collections.unmodifiableMap(
                new Object2ObjectArrayMap<>(requireNonNull(childChannelOptions, "childChannelOptions")));
        this.childChannelPipelineCustomizer =
                requireNonNull(childChannelPipelineCustomizer, "childChannelPipelineCustomizer");
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

        final List<VirtualHost> virtualHostsCopy = new ArrayList<>();
        if (!virtualHosts.isEmpty()) {
            for (VirtualHost h : virtualHosts) {
                virtualHostsCopy.add(h);
            }
        }
        // Add the default VirtualHost to the virtualHosts so that a user can retrieve all VirtualHosts
        // via virtualHosts(). i.e. no need to check defaultVirtualHost().
        virtualHostsCopy.add(defaultVirtualHost);

        if (virtualHosts.isEmpty()) {
            virtualHostAndPortMapping = null;
        } else {
            virtualHostAndPortMapping = buildDomainAndPortMapping(defaultVirtualHost, virtualHosts);
        }

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

        this.errorHandler = requireNonNull(errorHandler, "errorHandler");
        this.sslContexts = sslContexts;
        this.http1HeaderNaming = requireNonNull(http1HeaderNaming, "http1HeaderNaming");
        this.dependencyInjector = requireNonNull(dependencyInjector, "dependencyInjector");
        @SuppressWarnings("unchecked")
        final Function<String, String> castAbsoluteUriTransformer =
                (Function<String, String>) requireNonNull(absoluteUriTransformer, "absoluteUriTransformer");
        this.absoluteUriTransformer = castAbsoluteUriTransformer;
        this.unloggedExceptionsReportIntervalMillis = unloggedExceptionsReportIntervalMillis;
        this.shutdownSupports = ImmutableList.copyOf(requireNonNull(shutdownSupports, "shutdownSupports"));
    }

    private static Int2ObjectMap<Mapping<String, VirtualHost>> buildDomainAndPortMapping(
            VirtualHost defaultVirtualHost, List<VirtualHost> virtualHosts) {

        final List<VirtualHost> portMappingVhosts = virtualHosts.stream()
                                                                .filter(v -> v.port() > 0)
                                                                .collect(toImmutableList());
        final Map<Integer, VirtualHost> portMappingDefaultVhosts =
                portMappingVhosts.stream()
                                 .filter(v -> v.hostnamePattern().startsWith("*:"))
                                 .collect(toImmutableMap(VirtualHost::port, Function.identity()));

        final Map<Integer, DomainMappingBuilder<VirtualHost>> mappingsBuilder = new HashMap<>();
        for (VirtualHost virtualHost : portMappingVhosts) {
            final int port = virtualHost.port();
            // The default virtual host should be either '*' or '*:<port>'.
            final VirtualHost defaultVhost =
                    firstNonNull(portMappingDefaultVhosts.get(port), defaultVirtualHost);
            // Builds a 'DomainMappingBuilder' with 'defaultVhost' for the port if absent.
            final DomainMappingBuilder<VirtualHost> mappingBuilder =
                    mappingsBuilder.computeIfAbsent(port, key -> new DomainMappingBuilder<>(defaultVhost));

            if (defaultVhost == virtualHost) {
                // The 'virtualHost' was added already as a default value when creating 'DomainMappingBuilder'.
            } else {
                mappingBuilder.add(virtualHost.hostnamePattern(), virtualHost);
            }
        }

        final Int2ObjectMap<Mapping<String, VirtualHost>> mappings =
                new Int2ObjectOpenHashMap<>(mappingsBuilder.size() + 1);

        mappingsBuilder.forEach((port, builder) -> mappings.put(port.intValue(), builder.build()));
        // Add name-based virtual host mappings.
        mappings.put(-1, buildDomainMapping(defaultVirtualHost, virtualHosts));
        return mappings;
    }

    private static Mapping<String, VirtualHost> buildDomainMapping(VirtualHost defaultVirtualHost,
                                                                   List<VirtualHost> virtualHosts) {
        // Set virtual host definitions and initialize their domain name mapping.
        final DomainMappingBuilder<VirtualHost> mappingBuilder = new DomainMappingBuilder<>(defaultVirtualHost);
        for (VirtualHost h : virtualHosts) {
            if (h.port() > 0) {
                // A port-based virtual host will be handled by buildDomainAndPortMapping().
                continue;
            }
            mappingBuilder.add(h.hostnamePattern(), h);
        }
        return mappingBuilder.build();
    }

    private static BlockingTaskExecutor monitorBlockingTaskExecutor(BlockingTaskExecutor executor,
                                                                    MeterRegistry meterRegistry) {
        new ExecutorServiceMetrics(
                executor.unwrap(),
                "blockingTaskExecutor", "armeria", ImmutableList.of())
                .bindTo(meterRegistry);
        return executor;
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

    @Override
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

    @Override
    public List<ServerPort> ports() {
        return ports;
    }

    @Override
    public VirtualHost defaultVirtualHost() {
        return defaultVirtualHost;
    }

    @Override
    public List<VirtualHost> virtualHosts() {
        return virtualHosts;
    }

    @Override
    @Deprecated
    public VirtualHost findVirtualHost(String hostname) {
        if (virtualHostAndPortMapping == null) {
            return defaultVirtualHost;
        }
        final Mapping<String, VirtualHost> virtualHostMapping = virtualHostAndPortMapping.get(-1);
        return virtualHostMapping.map(hostname);
    }

    @Override
    public VirtualHost findVirtualHost(String hostname, int port) {
        if (virtualHostAndPortMapping == null) {
            return defaultVirtualHost;
        }

        final Mapping<String, VirtualHost> virtualHostMapping = virtualHostAndPortMapping.get(port);
        if (virtualHostMapping != null) {
            final VirtualHost virtualHost = virtualHostMapping.map(hostname + ':' + port);
            // Exclude the default virtual host from port-based virtual hosts.
            if (virtualHost != defaultVirtualHost) {
                return virtualHost;
            }
        }
        // No port-based virtual host is configured. Look for named-based virtual host.
        final Mapping<String, VirtualHost> nameBasedMapping = virtualHostAndPortMapping.get(-1);
        assert nameBasedMapping != null;
        return nameBasedMapping.map(hostname);
    }

    @Override
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

    @Override
    public List<ServiceConfig> serviceConfigs() {
        return services;
    }

    @Override
    public EventLoopGroup workerGroup() {
        return workerGroup;
    }

    @Override
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

    @Override
    public Map<ChannelOption<?>, ?> channelOptions() {
        return channelOptions;
    }

    @Override
    public Map<ChannelOption<?>, ?> childChannelOptions() {
        return childChannelOptions;
    }

    @Override
    public Consumer<? super ChannelPipeline> childChannelPipelineCustomizer() {
        return childChannelPipelineCustomizer;
    }

    @Override
    public int maxNumConnections() {
        return maxNumConnections;
    }

    @Override
    public long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    @Override
    public boolean keepAliveOnPing() {
        return keepAliveOnPing;
    }

    @Override
    public long pingIntervalMillis() {
        return pingIntervalMillis;
    }

    @Override
    public long maxConnectionAgeMillis() {
        return maxConnectionAgeMillis;
    }

    @Override
    public long connectionDrainDurationMicros() {
        return connectionDrainDurationMicros;
    }

    @Override
    public int maxNumRequestsPerConnection() {
        return maxNumRequestsPerConnection;
    }

    @Override
    public int http1MaxInitialLineLength() {
        return http1MaxInitialLineLength;
    }

    @Override
    public int http1MaxHeaderSize() {
        return http1MaxHeaderSize;
    }

    @Override
    public int http1MaxChunkSize() {
        return http1MaxChunkSize;
    }

    @Override
    public int http2InitialConnectionWindowSize() {
        return http2InitialConnectionWindowSize;
    }

    @Override
    public int http2InitialStreamWindowSize() {
        return http2InitialStreamWindowSize;
    }

    @Override
    public long http2MaxStreamsPerConnection() {
        return http2MaxStreamsPerConnection;
    }

    @Override
    public int http2MaxFrameSize() {
        return http2MaxFrameSize;
    }

    @Override
    public long http2MaxHeaderListSize() {
        return http2MaxHeaderListSize;
    }

    @Override
    public int http2MaxResetFramesPerWindow() {
        return http2MaxResetFramesPerWindow;
    }

    @Override
    public int http2MaxResetFramesWindowSeconds() {
        return http2MaxResetFramesWindowSeconds;
    }

    @Override
    public Duration gracefulShutdownQuietPeriod() {
        return gracefulShutdownQuietPeriod;
    }

    @Override
    public Duration gracefulShutdownTimeout() {
        return gracefulShutdownTimeout;
    }

    @Override
    public BlockingTaskExecutor blockingTaskExecutor() {
        return blockingTaskExecutor;
    }

    @Override
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Override
    public int proxyProtocolMaxTlvSize() {
        return proxyProtocolMaxTlvSize;
    }

    @Override
    public List<ClientAddressSource> clientAddressSources() {
        return clientAddressSources;
    }

    @Override
    public Predicate<? super InetAddress> clientAddressTrustedProxyFilter() {
        return clientAddressTrustedProxyFilter;
    }

    @Override
    public Predicate<? super InetAddress> clientAddressFilter() {
        return clientAddressFilter;
    }

    @Override
    public Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper() {
        return clientAddressMapper;
    }

    @Override
    public boolean isServerHeaderEnabled() {
        return enableServerHeader;
    }

    @Override
    public boolean isDateHeaderEnabled() {
        return enableDateHeader;
    }

    @Override
    public Function<RoutingContext, RequestId> requestIdGenerator() {
        return defaultVirtualHost.requestIdGenerator();
    }

    @Override
    public ServerErrorHandler errorHandler() {
        return errorHandler;
    }

    /**
     * Returns a map of SslContexts {@link SslContext}.
     */
    @Nullable
    Mapping<String, SslContext> sslContextMapping() {
        return sslContexts;
    }

    @Override
    public Http1HeaderNaming http1HeaderNaming() {
        return http1HeaderNaming;
    }

    @Override
    public DependencyInjector dependencyInjector() {
        return dependencyInjector;
    }

    @Override
    public Function<String, String> absoluteUriTransformer() {
        return absoluteUriTransformer;
    }

    @Override
    public long unhandledExceptionsReportIntervalMillis() {
        return unloggedExceptionsReportIntervalMillis;
    }

    @Override
    public long unloggedExceptionsReportIntervalMillis() {
        return unloggedExceptionsReportIntervalMillis;
    }

    @Override
    public ServerMetrics serverMetrics() {
        return serverMetrics;
    }

    List<ShutdownSupport> shutdownSupports() {
        return shutdownSupports;
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
                    blockingTaskExecutor(),
                    meterRegistry(), channelOptions(), childChannelOptions(),
                    clientAddressSources(), clientAddressTrustedProxyFilter(), clientAddressFilter(),
                    clientAddressMapper(),
                    isServerHeaderEnabled(), isDateHeaderEnabled(),
                    dependencyInjector(), absoluteUriTransformer(), unloggedExceptionsReportIntervalMillis(),
                    serverMetrics());
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
            @Nullable BlockingTaskExecutor blockingTaskExecutor,
            @Nullable MeterRegistry meterRegistry,
            Map<ChannelOption<?>, ?> channelOptions, Map<ChannelOption<?>, ?> childChannelOptions,
            List<ClientAddressSource> clientAddressSources,
            Predicate<? super InetAddress> clientAddressTrustedProxyFilter,
            Predicate<? super InetAddress> clientAddressFilter,
            Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper,
            boolean serverHeaderEnabled, boolean dateHeaderEnabled,
            @Nullable DependencyInjector dependencyInjector,
            Function<? super String, String> absoluteUriTransformer,
            long unloggedExceptionsReportIntervalMillis,
            ServerMetrics serverMetrics) {
        final StringBuilder buf = new StringBuilder();
        if (type != null) {
            buf.append(type.getSimpleName());
        }

        buf.append("(ports: [");

        boolean hasPorts = false;
        for (final ServerPort p : ports) {
            buf.append(ServerPort.toString(null, p.localAddress(), p.protocols(), p.portGroup()));
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
        if (blockingTaskExecutor != null) {
            buf.append(", blockingTaskExecutor: ");
            buf.append(blockingTaskExecutor);
        }
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
        if (dependencyInjector != null) {
            buf.append(", dependencyInjector: ");
            buf.append(dependencyInjector);
        }
        buf.append(", absoluteUriTransformer: ");
        buf.append(absoluteUriTransformer);
        buf.append(", unloggedExceptionsReportIntervalMillis: ");
        buf.append(unloggedExceptionsReportIntervalMillis);
        buf.append(", serverMetrics: ");
        buf.append(serverMetrics);
        buf.append(')');

        return buf.toString();
    }
}
