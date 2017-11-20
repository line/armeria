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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.internal.ConnectionLimitingHandler;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;
import io.netty.util.DomainNameMappingBuilder;

/**
 * {@link Server} configuration.
 */
public final class ServerConfig {

    /**
     * Initialized later by {@link Server} via {@link #setServer(Server)}.
     */
    private Server server;

    private final List<ServerPort> ports;
    private final VirtualHost defaultVirtualHost;
    private final List<VirtualHost> virtualHosts;
    private final DomainNameMapping<VirtualHost> virtualHostMapping;
    private final List<ServiceConfig> services;

    private final EventLoopGroup workerGroup;
    private final boolean shutdownWorkerGroupOnStop;
    private final int maxNumConnections;
    private final long defaultRequestTimeoutMillis;
    private final long idleTimeoutMillis;
    private final long defaultMaxRequestLength;
    private final int defaultMaxHttp1InitialLineLength;
    private final int defaultMaxHttp1HeaderSize;
    private final int defaultMaxHttp1ChunkSize;

    private final Duration gracefulShutdownQuietPeriod;
    private final Duration gracefulShutdownTimeout;

    private final ExecutorService blockingTaskExecutor;

    private final MeterRegistry meterRegistry;

    private final String serviceLoggerPrefix;

    private String strVal;

    ServerConfig(
            Iterable<ServerPort> ports,
            VirtualHost defaultVirtualHost, Iterable<VirtualHost> virtualHosts,
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnStop,
            int maxNumConnections, long idleTimeoutMillis,
            long defaultRequestTimeoutMillis, long defaultMaxRequestLength,
            int defaultMaxHttp1InitialLineLength, int defaultMaxHttp1HeaderSize, int defaultMaxHttp1ChunkSize,
            Duration gracefulShutdownQuietPeriod, Duration gracefulShutdownTimeout,
            Executor blockingTaskExecutor, MeterRegistry meterRegistry, String serviceLoggerPrefix) {

        requireNonNull(ports, "ports");
        requireNonNull(defaultVirtualHost, "defaultVirtualHost");
        requireNonNull(virtualHosts, "virtualHosts");

        // Set the primitive properties.
        this.workerGroup = requireNonNull(workerGroup, "workerGroup");
        this.shutdownWorkerGroupOnStop = shutdownWorkerGroupOnStop;
        this.maxNumConnections = validateMaxNumConnections(maxNumConnections);
        this.idleTimeoutMillis = validateIdleTimeoutMillis(idleTimeoutMillis);
        this.defaultRequestTimeoutMillis = validateDefaultRequestTimeoutMillis(defaultRequestTimeoutMillis);
        this.defaultMaxRequestLength = validateDefaultMaxRequestLength(defaultMaxRequestLength);
        this.defaultMaxHttp1InitialLineLength = validateNonNegative(
                defaultMaxHttp1InitialLineLength, "defaultMaxHttp1InitialLineLength");
        this.defaultMaxHttp1HeaderSize = validateNonNegative(
                defaultMaxHttp1HeaderSize, "defaultMaxHttp1HeaderSize");
        this.defaultMaxHttp1ChunkSize = validateNonNegative(
                defaultMaxHttp1ChunkSize, "defaultMaxHttp1ChunkSize");
        this.gracefulShutdownQuietPeriod = validateNonNegative(requireNonNull(
                gracefulShutdownQuietPeriod), "gracefulShutdownQuietPeriod");
        this.gracefulShutdownTimeout = validateNonNegative(requireNonNull(
                gracefulShutdownTimeout), "gracefulShutdownTimeout");
        validateGreaterThanOrEqual(gracefulShutdownTimeout, "gracefulShutdownTimeout",
                                   gracefulShutdownQuietPeriod, "gracefulShutdownQuietPeriod");

        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        if (blockingTaskExecutor instanceof ExecutorService) {
            this.blockingTaskExecutor = new InterminableExecutorService((ExecutorService) blockingTaskExecutor);
        } else {
            this.blockingTaskExecutor = new ExecutorBasedExecutorService(blockingTaskExecutor);
        }

        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        this.serviceLoggerPrefix = ServiceConfig.validateLoggerName(serviceLoggerPrefix, "serviceLoggerPrefix");

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

        // Set virtual host definitions and initialize their domain name mapping.
        defaultVirtualHost = normalizeDefaultVirtualHost(defaultVirtualHost, portsCopy);
        final DomainNameMappingBuilder<VirtualHost> mappingBuilder =
                new DomainNameMappingBuilder<>(defaultVirtualHost);
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

    static long validateDefaultRequestTimeoutMillis(long defaultRequestTimeoutMillis) {
        if (defaultRequestTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "defaultRequestTimeoutMillis: " + defaultRequestTimeoutMillis + " (expected: >= 0)");
        }
        return defaultRequestTimeoutMillis;
    }

    static long validateDefaultMaxRequestLength(long defaultMaxRequestLength) {
        if (defaultMaxRequestLength < 0) {
            throw new IllegalArgumentException(
                    "defaultMaxRequestLength: " + defaultMaxRequestLength + " (expected: >= 0)");
        }
        return defaultMaxRequestLength;
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

    private static VirtualHost normalizeDefaultVirtualHost(VirtualHost h, List<ServerPort> ports) {
        final SslContext sslCtx = h.sslContext();
        // The default virtual host must have sslContext set if TLS is in use.
        if (sslCtx == null && ports.stream().anyMatch(p -> p.protocol().isTls())) {
            throw new IllegalArgumentException(
                    "defaultVirtualHost must have sslContext set when TLS is enabled.");
        }

        return new VirtualHost(
                h.defaultHostname(), "*", sslCtx,
                h.serviceConfigs().stream().map(
                        e -> new ServiceConfig(e.pathMapping(), e.service(), e.loggerName().orElse(null)))
                 .collect(Collectors.toList()), h.producibleMediaTypes());
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
     * Finds the {@link List} of {@link VirtualHost}s that contains the specified {@link Service}. If there's
     * no match, an empty {@link List} is returned. Note that this is potentially an expensive operation and
     * thus should not be used in a performance-sensitive path.
     */
    public List<VirtualHost> findVirtualHosts(Service<?, ?> service) {
        requireNonNull(service, "service");

        @SuppressWarnings("rawtypes")
        final Class<? extends Service> serviceType = service.getClass();
        final List<VirtualHost> res = new ArrayList<>();
        for (VirtualHost h : virtualHosts) {
            for (ServiceConfig c : h.serviceConfigs()) {
                // Consider the case where the specified service is decorated before being added.
                final Service<?, ?> s = c.service();
                @SuppressWarnings("rawtypes")
                Optional<? extends Service> sOpt = s.as(serviceType);
                if (!sOpt.isPresent()) {
                    continue;
                }

                if (sOpt.get() == service) {
                    res.add(c.virtualHost());
                    break;
                }
            }
        }

        return res;
    }

    /**
     * Returns the information of all available {@link Service}s in the {@link Server}.
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
     * Returns the default timeout of a request.
     */
    public long defaultRequestTimeoutMillis() {
        return defaultRequestTimeoutMillis;
    }

    /**
     * Returns the default maximum allowed length of the content decoded at the session layer.
     * e.g. the content length of an HTTP request.
     */
    public long defaultMaxRequestLength() {
        return defaultMaxRequestLength;
    }

    /**
     * Returns the default maximum length of an HTTP/1 response initial line.
     */
    public int defaultMaxHttp1InitialLineLength() {
        return defaultMaxHttp1InitialLineLength;
    }

    /**
     * Returns the default maximum length of all headers in an HTTP/1 response.
     */
    public int defaultMaxHttp1HeaderSize() {
        return defaultMaxHttp1HeaderSize;
    }

    /**
     * Returns the default maximum length of each chunk in an HTTP/1 response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     */
    public int defaultMaxHttp1ChunkSize() {
        return defaultMaxHttp1ChunkSize;
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
     * Returns the {@link ExecutorService} dedicated to the execution of blocking tasks or invocations.
     * Note that the {@link ExecutorService} returned by this method does not set the
     * {@link ServiceRequestContext} when executing a submitted task.
     * Use {@link ServiceRequestContext#blockingTaskExecutor()} if possible.
     */
    public ExecutorService blockingTaskExecutor() {
        return blockingTaskExecutor;
    }

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * Returns the prefix of {@linkplain ServiceRequestContext#logger() service logger}'s names.
     */
    public String serviceLoggerPrefix() {
        return serviceLoggerPrefix;
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal == null) {
            this.strVal = strVal = toString(
                    getClass(), ports(), null, virtualHosts(),
                    workerGroup(), shutdownWorkerGroupOnStop(),
                    maxNumConnections(), idleTimeoutMillis(),
                    defaultRequestTimeoutMillis(), defaultMaxRequestLength(),
                    defaultMaxHttp1InitialLineLength(), defaultMaxHttp1HeaderSize(), defaultMaxHttp1ChunkSize(),
                    gracefulShutdownQuietPeriod(), gracefulShutdownTimeout(),
                    blockingTaskExecutor(), meterRegistry(), serviceLoggerPrefix());
        }

        return strVal;
    }

    static String toString(
            Class<?> type,
            Iterable<ServerPort> ports, VirtualHost defaultVirtualHost, List<VirtualHost> virtualHosts,
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnStop,
            int maxNumConnections, long idleTimeoutMillis, long defaultRequestTimeoutMillis,
            long defaultMaxRequestLength, long defaultMaxHttp1InitialLineLength,
            long defaultMaxHttp1HeaderSize, long defaultMaxHttp1ChunkSize,
            Duration gracefulShutdownQuietPeriod, Duration gracefulShutdownTimeout,
            Executor blockingTaskExecutor, MeterRegistry meterRegistry, String serviceLoggerPrefix) {

        StringBuilder buf = new StringBuilder();
        if (type != null) {
            buf.append(type.getSimpleName());
        }

        buf.append("(ports: [");

        boolean hasPorts = false;
        for (ServerPort p : ports) {
            buf.append(ServerPort.toString(null, p.localAddress(), p.protocol()));
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
            virtualHosts.forEach(c -> {
                buf.append(VirtualHost.toString(null, c.defaultHostname(), c.hostnamePattern(),
                                                c.sslContext(), c.serviceConfigs()));
                buf.append(", ");
            });

            if (defaultVirtualHost != null) {
                buf.append(VirtualHost.toString(null, defaultVirtualHost.defaultHostname(), "*",
                                                defaultVirtualHost.sslContext(),
                                                defaultVirtualHost.serviceConfigs()));
            } else {
                buf.setLength(buf.length() - 2);
            }
        } else if (defaultVirtualHost != null) {
            buf.append(VirtualHost.toString(null, defaultVirtualHost.defaultHostname(), "*",
                                            defaultVirtualHost.sslContext(),
                                            defaultVirtualHost.serviceConfigs()));
        }

        buf.append("], workerGroup: ");
        buf.append(workerGroup);
        buf.append(" (shutdownOnStop=");
        buf.append(shutdownWorkerGroupOnStop);
        buf.append("), maxNumConnections: ");
        buf.append(maxNumConnections);
        buf.append(", idleTimeout: ");
        buf.append(idleTimeoutMillis);
        buf.append("ms, defaultRequestTimeout: ");
        buf.append(defaultRequestTimeoutMillis);
        buf.append("ms, defaultMaxRequestLength: ");
        buf.append(defaultMaxRequestLength);
        buf.append("B, defaultMaxHttp1InitialLineLength: ");
        buf.append(defaultMaxHttp1InitialLineLength);
        buf.append("B, defaultMaxHttp1HeaderSize: ");
        buf.append(defaultMaxHttp1HeaderSize);
        buf.append("B, defaultMaxHttp1ChunkSize: ");
        buf.append(defaultMaxHttp1ChunkSize);
        buf.append("B, gracefulShutdownQuietPeriod: ");
        buf.append(gracefulShutdownQuietPeriod);
        buf.append(", gracefulShutdownTimeout: ");
        buf.append(gracefulShutdownTimeout);
        buf.append(", blockingTaskExecutor: ");
        buf.append(blockingTaskExecutor);
        if (meterRegistry != null) {
            buf.append(", meterRegistry: ");
            buf.append(meterRegistry);
        }
        buf.append(", serviceLoggerPrefix: ");
        buf.append(serviceLoggerPrefix);
        buf.append(')');

        return buf.toString();
    }
}
