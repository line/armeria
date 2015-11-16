/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TimeoutPolicy;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Promise;

/**
 * Builds a new {@link Server} and its {@link ServerConfig}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * // Add a port to listen
 * sb.port(8080, SessionProtocol.HTTP);
 * // Build and add a virtual host.
 * sb.virtualHost(new VirtualHost("*.foo.com").serviceAt(...).build());
 * // Build and add the default virtual host.
 * sb.defaultVirtualHost(new VirtualHost().serviceAt(...).build());
 * // Build a server.
 * Server s = sb.build();
 * }</pre>
 *
 * @see VirtualHostBuilder
 */
public final class ServerBuilder {

    private static final VirtualHost DEFAULT_VIRTUAL_HOST = new VirtualHostBuilder("*").build();
    private static final int DEFAULT_NUM_WORKERS;
    private static final int DEFAULT_MAX_PENDING_REQUESTS = 8;
    private static final int DEFAULT_MAX_CONNECTIONS = 65536;
    private static final TimeoutPolicy DEFAULT_REQUEST_TIMEOUT_POLICY =
            TimeoutPolicy.ofFixed(Duration.ofSeconds(10));
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();
    private static final int DEFAULT_MAX_FRAME_LENGTH = 1048576;
    // Defaults to no graceful shutdown.
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ZERO;
    private static final int DEFAULT_MAX_BLOCKING_TASK_THREADS = 200; // from Tomcat's maxThreads.

    static {
        String value = System.getProperty("io.netty.eventLoopThreads", "0");
        final int fallbackDefaultNumWorkers = Runtime.getRuntime().availableProcessors() * 2;
        int defaultNumWorkers;
        try {
            defaultNumWorkers = Integer.parseInt(value);
            if (defaultNumWorkers <= 0) {
                defaultNumWorkers = fallbackDefaultNumWorkers;
            }
        } catch (Exception ignored) {
            defaultNumWorkers = fallbackDefaultNumWorkers;
        }

        DEFAULT_NUM_WORKERS = defaultNumWorkers;
    }

    private static Executor defaultBlockingTaskExecutor() {
        return DefaultBlockingTaskExecutorHolder.INSTANCE;
    }

    private static final class DefaultBlockingTaskExecutorHolder {
        static final Executor INSTANCE = new ThreadPoolExecutor(
                0, DEFAULT_MAX_BLOCKING_TASK_THREADS,
                60, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                new DefaultThreadFactory("armeria-blocking-tasks", true));
    }

    private final List<ServerPort> ports = new ArrayList<>();
    private final List<VirtualHost> virtualHosts = new ArrayList<>();

    private VirtualHost defaultVirtualHost = DEFAULT_VIRTUAL_HOST;
    private int numWorkers = DEFAULT_NUM_WORKERS;
    private int maxPendingRequests = DEFAULT_MAX_PENDING_REQUESTS;
    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private TimeoutPolicy requestTimeoutPolicy = DEFAULT_REQUEST_TIMEOUT_POLICY;
    @SuppressWarnings("RedundantFieldInitialization")
    private long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;
    private int maxFrameLength = DEFAULT_MAX_FRAME_LENGTH;
    private Duration gracefulShutdownQuietPeriod = DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD;
    private Duration gracefulShutdownTimeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;
    private Executor blockingTaskExecutor;

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}.
     */
    public ServerBuilder port(int port, SessionProtocol protocol) {
        ports.add(new ServerPort(port, protocol));
        return this;
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}.
     */
    public ServerBuilder port(InetSocketAddress localAddress, SessionProtocol protocol) {
        ports.add(new ServerPort(localAddress, protocol));
        return this;
    }

    /**
     * Adds the specified {@link ServerPort}.
     */
    public ServerBuilder port(ServerPort port) {
        ports.add(requireNonNull(port, "port"));
        return this;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>
     * specified by {@link VirtualHost}.
     */
    public ServerBuilder virtualHost(VirtualHost virtualHost) {
        virtualHosts.add(requireNonNull(virtualHost, "virtualHost"));
        return this;
    }

    /**
     * Sets the default {@link VirtualHost}, which is used when no other {@link VirtualHost}s match the
     * host name of a client request. e.g. the {@code "Host"} header in HTTP or host name in TLS SNI extension
     *
     * @see #virtualHost(VirtualHost)
     */
    public ServerBuilder defaultVirtualHost(VirtualHost defaultVirtualHost) {
        requireNonNull(defaultVirtualHost, "defaultVirtualHost");
        this.defaultVirtualHost = defaultVirtualHost;
        return this;
    }

    /**
     * Sets the number of worker threads that performs socket I/O and runs
     * {@link ServiceInvocationHandler#invoke(ServiceInvocationContext, Executor, Promise)}.
     */
    public ServerBuilder numWorkers(int numWorkers) {
        this.numWorkers = ServerConfig.validateNumWorkers(numWorkers);
        return this;
    }

    /**
     * Sets the maximum allowed number of pending requests.
     */
    public ServerBuilder maxPendingRequests(int maxPendingRequests) {
        this.maxPendingRequests = ServerConfig.validateMaxPendingRequests(maxPendingRequests);
        return this;
    }

    /**
     * Sets the maximum allowed number of open connections.
     */
    public ServerBuilder maxConnections(int maxConnections) {
        this.maxConnections = ServerConfig.validateMaxConnections(maxConnections);
        return this;
    }

    /**
     * Sets the timeout of a request in milliseconds.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ServerBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        return requestTimeout(Duration.ofMillis(requestTimeoutMillis));
    }

    /**
     * Sets the timeout of a request.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    public ServerBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeout(TimeoutPolicy.ofFixed(requireNonNull(requestTimeout, "requestTimeout")));
    }

    /**
     * Sets the {@link TimeoutPolicy} of a request.
     */
    public ServerBuilder requestTimeout(TimeoutPolicy requestTimeoutPolicy) {
        this.requestTimeoutPolicy = requireNonNull(requestTimeoutPolicy, "requestTimeoutPolicy");
        return this;
    }

    /**
     * Sets the idle timeout of a connection in milliseconds.
     *
     * @param idleTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ServerBuilder idleTimeoutMillis(long idleTimeoutMillis) {
        return idleTimeout(Duration.ofMillis(idleTimeoutMillis));
    }

    /**
     * Sets the idle timeout of a connection.
     *
     * @param idleTimeout the timeout. {@code 0} disables the timeout.
     */
    public ServerBuilder idleTimeout(Duration idleTimeout) {
        requireNonNull(idleTimeout, "idleTimeout");
        idleTimeoutMillis = ServerConfig.validateIdleTimeoutMillis(idleTimeout.toMillis());
        return this;
    }

    /**
     * Sets the amount of time to wait after calling {@link Server#stop()} for
     * requests to go away before actually shutting down.
     *
     * @param quietPeriodMillis the number of milliseconds to wait for active
     *     requests to go end before shutting down. 0 means the server will
     *     stop right away without waiting.
     * @param timeoutMillis the number of milliseconds to wait before shutting
     *     down the server regardless of active requests. This should be set to
     *     a time greater than {@code quietPeriodMillis} to ensure the server
     *     shuts down even if there is a stuck request.
     */
    public ServerBuilder gracefulShutdownTimeout(long quietPeriodMillis, long timeoutMillis) {
        return gracefulShutdownTimeout(
                Duration.ofMillis(quietPeriodMillis), Duration.ofMillis(timeoutMillis));
    }

    /**
     * Sets the amount of time to wait after calling {@link Server#stop()} for
     * requests to go away before actually shutting down.
     *
     * @param quietPeriod the number of milliseconds to wait for active
     *     requests to go end before shutting down. {@link Duration#ZERO} means
     *     the server will stop right away without waiting.
     * @param timeout the number of milliseconds to wait before shutting
     *     down the server regardless of active requests. This should be set to
     *     a time greater than {@code quietPeriod} to ensure the server shuts
     *     down even if there is a stuck request.
     */
    public ServerBuilder gracefulShutdownTimeout(Duration quietPeriod, Duration timeout) {
        requireNonNull(quietPeriod, "quietPeriod");
        requireNonNull(timeout, "timeout");
        gracefulShutdownQuietPeriod = ServerConfig.validateNonNegative(quietPeriod, "quietPeriod");
        gracefulShutdownTimeout = ServerConfig.validateNonNegative(timeout, "timeout");
        ServerConfig.validateGreaterThanOrEqual(gracefulShutdownTimeout, "quietPeriod",
                                                gracefulShutdownQuietPeriod, "timeout");
        return this;
    }

    /**
     * Sets the {@link Executor} dedicated to the execution of blocking tasks or invocations.
     * If not set, the global default thread pool is used instead.
     */
    public ServerBuilder blockingTaskExecutor(Executor blockingTaskExecutor) {
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        return this;
    }

    /**
     * Sets the maximum allowed length of the frame (or the content) decoded at the session layer. e.g. the
     * content of an HTTP request.
     */
    public ServerBuilder maxFrameLength(int maxFrameLength) {
        this.maxFrameLength = ServerConfig.validateMaxFrameLength(maxFrameLength);
        return this;
    }

    /**
     * Creates a new {@link Server} with the configuration properties set so far.
     */
    public Server build() {
        Executor blockingTaskExecutor = this.blockingTaskExecutor;
        if (blockingTaskExecutor == null) {
            blockingTaskExecutor = defaultBlockingTaskExecutor();
        }

        return new Server(new ServerConfig(
                ports, defaultVirtualHost, virtualHosts, numWorkers, maxPendingRequests, maxConnections,
                requestTimeoutPolicy, idleTimeoutMillis, maxFrameLength, gracefulShutdownQuietPeriod,
                gracefulShutdownTimeout, blockingTaskExecutor));
    }

    @Override
    public String toString() {
        return ServerConfig.toString(
                getClass(), ports, defaultVirtualHost, virtualHosts,
                numWorkers, maxPendingRequests, maxConnections, requestTimeoutPolicy, idleTimeoutMillis,
                maxFrameLength, gracefulShutdownQuietPeriod, gracefulShutdownTimeout, blockingTaskExecutor);
    }
}
