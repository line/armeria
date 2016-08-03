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

import static com.linecorp.armeria.server.ServerConfig.validateDefaultMaxRequestLength;
import static com.linecorp.armeria.server.ServerConfig.validateDefaultRequestTimeoutMillis;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Builds a new {@link Server} and its {@link ServerConfig}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * // Add a port to listen
 * sb.port(8080, SessionProtocol.HTTP);
 * // Build and add a virtual host.
 * sb.virtualHost(new VirtualHost("*.foo.com").serviceAt(...).build());
 * // Add services to the default virtual host.
 * sb.serviceAt(...);
 * sb.serviceUnder(...);
 * // Build a server.
 * Server s = sb.build();
 * }</pre>
 *
 * @see VirtualHostBuilder
 */
public final class ServerBuilder {

    private static final int DEFAULT_NUM_WORKERS;
    private static final int DEFAULT_MAX_PENDING_REQUESTS = 8;
    private static final int DEFAULT_MAX_CONNECTIONS = 65536;
    // Use slightly greater value than the client default so that clients close the connection more often.
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = Duration.ofSeconds(15).toMillis();
    private static final long DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();
    private static final long DEFAULT_DEFAULT_MAX_REQUEST_LENGTH = 10 * 1024 * 1024; // 10 MB
    // Defaults to no graceful shutdown.
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ZERO;
    private static final int DEFAULT_MAX_BLOCKING_TASK_THREADS = 200; // from Tomcat's maxThreads.
    private static final String DEFAULT_SERVICE_LOGGER_PREFIX = "armeria.services";

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
    private final VirtualHostBuilder defaultVirtualHostBuilder = new VirtualHostBuilder();
    private boolean updatedDefaultVirtualHostBuilder;

    private VirtualHost defaultVirtualHost;
    private int numWorkers = DEFAULT_NUM_WORKERS;
    private int maxPendingRequests = DEFAULT_MAX_PENDING_REQUESTS;
    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    @SuppressWarnings("RedundantFieldInitialization")
    private long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;
    private long defaultRequestTimeoutMillis = DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS;
    private long defaultMaxRequestLength = DEFAULT_DEFAULT_MAX_REQUEST_LENGTH;
    private Duration gracefulShutdownQuietPeriod = DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD;
    private Duration gracefulShutdownTimeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;
    private Executor blockingTaskExecutor;
    private String serviceLoggerPrefix = DEFAULT_SERVICE_LOGGER_PREFIX;

    private Function<Service<Request, Response>, Service<Request, Response>> decorator;

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}. If no port is added (i.e. no {@code port()}
     * method is called), a default of {@code 0} (randomly-assigned port) and {@link SessionProtocol#HTTP}
     * will be used.
     */
    public ServerBuilder port(int port, SessionProtocol protocol) {
        ports.add(new ServerPort(port, protocol));
        return this;
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}. If no port is added (i.e. no {@code port()} method is called), a default of
     * {@code 0} (randomly-assigned port) and {@link SessionProtocol#HTTP} will be used.
     */
    public ServerBuilder port(InetSocketAddress localAddress, SessionProtocol protocol) {
        ports.add(new ServerPort(localAddress, protocol));
        return this;
    }

    /**
     * Adds the specified {@link ServerPort}. If no port is added (i.e. no {@code port()} method is called),
     * a default of {@code 0} (randomly-assigned port) and {@link SessionProtocol#HTTP} will be used.
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
     * Sets the number of worker threads that performs socket I/O and runs
     * {@link Service#serve(ServiceRequestContext, Request)}.
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
     * Sets the default timeout of a request in milliseconds.
     *
     * @param defaultRequestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ServerBuilder defaultRequestTimeoutMillis(long defaultRequestTimeoutMillis) {
        validateDefaultRequestTimeoutMillis(defaultRequestTimeoutMillis);
        this.defaultRequestTimeoutMillis = defaultRequestTimeoutMillis;
        return this;
    }

    /**
     * Sets the default timeout of a request.
     *
     * @param defaultRequestTimeout the timeout. {@code 0} disables the timeout.
     */
    public ServerBuilder defaultRequestTimeout(Duration defaultRequestTimeout) {
        return defaultRequestTimeoutMillis(
                requireNonNull(defaultRequestTimeout, "defaultRequestTimeout").toMillis());
    }

    /**
     * Sets the maximum allowed length of the content decoded at the session layer.
     * e.g. the content length of an HTTP request.
     *
     *  @param defaultMaxRequestLength the maximum allowed length. {@code 0} disables the length limit.
     */
    public ServerBuilder defaultMaxRequestLength(long defaultMaxRequestLength) {
        validateDefaultMaxRequestLength(defaultMaxRequestLength);
        this.defaultMaxRequestLength = defaultMaxRequestLength;
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
     * Sets the prefix of {@linkplain ServiceRequestContext#logger() service logger} names.
     * The default value is "{@value #DEFAULT_SERVICE_LOGGER_PREFIX}". A service logger name prefix must be
     * a string of valid Java identifier names concatenated by period ({@code '.'}), such as a package name.
     */
    public ServerBuilder serviceLoggerPrefix(String serviceLoggerPrefix) {
        this.serviceLoggerPrefix = ServiceConfig.validateLoggerName(serviceLoggerPrefix, "serviceLoggerPrefix");
        return this;
    }

    /**
     * Sets the {@link SslContext} of the default {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder sslContext(SslContext sslContext) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.sslContext(sslContext);
        return this;
    }

    /**
     * Sets the {@link SslContext} of the default {@link VirtualHost} from the specified
     * {@link SessionProtocol}, {@code keyCertChainFile} and cleartext {@code keyFile}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder sslContext(
            SessionProtocol protocol, File keyCertChainFile, File keyFile) throws SSLException {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.sslContext(protocol, keyCertChainFile, keyFile);
        return this;
    }

    /**
     * Sets the {@link SslContext} of the default {@link VirtualHost} from the specified
     * {@link SessionProtocol}, {@code keyCertChainFile}, {@code keyFile} and {@code keyPassword}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder sslContext(
            SessionProtocol protocol,
            File keyCertChainFile, File keyFile, String keyPassword) throws SSLException {

        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.sslContext(protocol, keyCertChainFile, keyFile, keyPassword);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified exact path of the default {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder serviceAt(String exactPath, Service<?, ?> service) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.serviceAt(exactPath, service);
        return this;
    }

    /**
     * Binds the specified {@link Service} under the specified directory of the default {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder serviceUnder(String pathPrefix, Service<?, ?> service) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.serviceUnder(pathPrefix, service);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping} of the default
     * {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder service(PathMapping pathMapping, Service<?, ?> service) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(pathMapping, service);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping} of the default
     * {@link VirtualHost}.
     *
     * @param loggerName the name of the {@linkplain ServiceRequestContext#logger() service logger};
     *                   must be a string of valid Java identifier names concatenated by period ({@code '.'}),
     *                   such as a package name or a fully-qualified class name
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder service(PathMapping pathMapping, Service<?, ?> service, String loggerName) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(pathMapping, service, loggerName);
        return this;
    }

    private void defaultVirtualHostBuilderUpdated() {
        updatedDefaultVirtualHostBuilder = true;
        if (defaultVirtualHost != null) {
            throw new IllegalStateException("ServerBuilder.defaultVirtualHost() invoked already.");
        }
    }

    /**
     * Sets the default {@link VirtualHost}, which is used when no other {@link VirtualHost}s match the
     * host name of a client request. e.g. the {@code "Host"} header in HTTP or host name in TLS SNI extension
     *
     * @throws IllegalStateException if other default {@link VirtualHost} builder methods have been invoked
     * already, including:
     * <ul>
     *   <li>{@link #sslContext(SslContext)}</li>
     *   <li>{@link #serviceAt(String, Service)}</li>
     *   <li>{@link #serviceUnder(String, Service)}</li>
     *   <li>{@link #service(PathMapping, Service)}</li>
     * </ul>
     *
     * @see #virtualHost(VirtualHost)
     */
    public ServerBuilder defaultVirtualHost(VirtualHost defaultVirtualHost) {
        requireNonNull(defaultVirtualHost, "defaultVirtualHost");
        if (updatedDefaultVirtualHostBuilder) {
            throw new IllegalStateException("invoked other default VirtualHost builder methods already");
        }

        this.defaultVirtualHost = defaultVirtualHost;
        return this;
    }

    /**
     * Decorates all {@link Service}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates a {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<T_I, T_O>, T_I extends Request, T_O extends Response,
            R extends Service<R_I, R_O>, R_I extends Request, R_O extends Response> ServerBuilder decorator(
            Function<T, R> decorator) {

        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        Function<Service<Request, Response>, Service<Request, Response>> castDecorator =
                (Function<Service<Request, Response>, Service<Request, Response>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

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

        final List<ServerPort> ports =
                !this.ports.isEmpty() ? this.ports
                                      : Collections.singletonList(new ServerPort(0, SessionProtocol.HTTP));

        final VirtualHost defaultVirtualHost;
        if (this.defaultVirtualHost != null) {
            defaultVirtualHost = this.defaultVirtualHost.decorate(decorator);
        } else {
            defaultVirtualHost = defaultVirtualHostBuilder.build().decorate(decorator);
        }

        final List<VirtualHost> virtualHosts;
        if (decorator != null) {
            virtualHosts = this.virtualHosts.stream()
                                            .map(h -> h.decorate(decorator))
                                            .collect(Collectors.toList());
        } else {
            virtualHosts = this.virtualHosts;
        }

        return new Server(new ServerConfig(
                ports, defaultVirtualHost, virtualHosts, numWorkers, maxPendingRequests, maxConnections,
                idleTimeoutMillis, defaultRequestTimeoutMillis, defaultMaxRequestLength,
                gracefulShutdownQuietPeriod, gracefulShutdownTimeout,
                blockingTaskExecutor, serviceLoggerPrefix));
    }

    @Override
    public String toString() {
        return ServerConfig.toString(
                getClass(), ports, defaultVirtualHost, virtualHosts,
                numWorkers, maxPendingRequests, maxConnections, idleTimeoutMillis,
                defaultRequestTimeoutMillis, defaultMaxRequestLength,
                gracefulShutdownQuietPeriod, gracefulShutdownTimeout,
                blockingTaskExecutor, serviceLoggerPrefix);
    }
}
