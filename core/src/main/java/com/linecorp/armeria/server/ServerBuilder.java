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

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.server.ServerConfig.validateDefaultMaxRequestLength;
import static com.linecorp.armeria.server.ServerConfig.validateDefaultRequestTimeoutMillis;
import static com.linecorp.armeria.server.ServerConfig.validateNonNegative;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.annotation.ResponseConverter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;

/**
 * Builds a new {@link Server} and its {@link ServerConfig}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * // Add a port to listen
 * sb.port(8080, SessionProtocol.HTTP);
 * // Build and add a virtual host.
 * sb.virtualHost(new VirtualHostBuilder("*.foo.com").service(...).build());
 * // Add services to the default virtual host.
 * sb.service(...);
 * sb.serviceUnder(...);
 * // Build a server.
 * Server s = sb.build();
 * }</pre>
 *
 * <h2>Example 2</h2>
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * Server server =
 *      sb.port(8080, SessionProtocol.HTTP) // Add a port to listen
 *      .withDefaultVirtualHost() // Add services to the default virtual host.
 *          .service(...)
 *          .serviceUnder(...)
 *      .and().withVirtualHost("*.foo.com") // Add a another virtual host.
 *          .service(...)
 *          .serviceUnder(...)
 *      .and().build(); // Build a server.
 * }</pre>
 * @see VirtualHostBuilder
 */
public final class ServerBuilder {

    // Use Integer.MAX_VALUE not to limit open connections by default.
    private static final int DEFAULT_MAX_NUM_CONNECTIONS = Integer.MAX_VALUE;

    // Defaults to no graceful shutdown.
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ZERO;
    private static final String DEFAULT_SERVICE_LOGGER_PREFIX = "armeria.services";

    private final List<ServerPort> ports = new ArrayList<>();
    private final List<ServerListener> serverListeners = new ArrayList<>();
    private final List<VirtualHost> virtualHosts = new ArrayList<>();
    private final List<ChainedVirtualHostBuilder> virtualHostBuilders = new ArrayList<>();
    private final ChainedVirtualHostBuilder defaultVirtualHostBuilder = new ChainedVirtualHostBuilder(this);
    private boolean updatedDefaultVirtualHostBuilder;

    private VirtualHost defaultVirtualHost;
    private EventLoopGroup workerGroup = CommonPools.workerGroup();
    private boolean shutdownWorkerGroupOnStop;
    private int maxNumConnections = DEFAULT_MAX_NUM_CONNECTIONS;
    private long idleTimeoutMillis = Flags.defaultServerIdleTimeoutMillis();
    private long defaultRequestTimeoutMillis = Flags.defaultRequestTimeoutMillis();
    private long defaultMaxRequestLength = Flags.defaultMaxRequestLength();
    private int maxHttp1InitialLineLength = Flags.defaultMaxHttp1InitialLineLength();
    private int maxHttp1HeaderSize = Flags.defaultMaxHttp1HeaderSize();
    private int maxHttp1ChunkSize = Flags.defaultMaxHttp1ChunkSize();
    private Duration gracefulShutdownQuietPeriod = DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD;
    private Duration gracefulShutdownTimeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;
    private Executor blockingTaskExecutor = CommonPools.blockingTaskExecutor();
    private MeterRegistry meterRegistry = Metrics.globalRegistry;
    private String serviceLoggerPrefix = DEFAULT_SERVICE_LOGGER_PREFIX;

    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified protocol. If no port is added (i.e. no {@code port()} method is called),
     * a default of {@code 0} (randomly-assigned port) and {@code "http"} will be used.
     */
    public ServerBuilder port(int port, String protocol) {
        return port(port, SessionProtocol.of(requireNonNull(protocol, "protocol")));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}. If no port is added (i.e. no {@code port()}
     * method is called), a default of {@code 0} (randomly-assigned port) and {@code "http"} will be used.
     */
    public ServerBuilder port(int port, SessionProtocol protocol) {
        ports.add(new ServerPort(port, protocol));
        return this;
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * protocol. If no port is added (i.e. no {@code port()} method is called), a default of {@code 0}
     * (randomly-assigned port) and {@code "http"} will be used.
     */
    public ServerBuilder port(InetSocketAddress localAddress, String protocol) {
        return port(localAddress, SessionProtocol.of(requireNonNull(protocol, "protocol")));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}. If no port is added (i.e. no {@code port()} method is called), a default of
     * {@code 0} (randomly-assigned port) and {@code "http"} will be used.
     */
    public ServerBuilder port(InetSocketAddress localAddress, SessionProtocol protocol) {
        ports.add(new ServerPort(localAddress, protocol));
        return this;
    }

    /**
     * Adds the specified {@link ServerPort}. If no port is added (i.e. no {@code port()} method is called),
     * a default of {@code 0} (randomly-assigned port) and {@code "http"} will be used.
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
     * Sets the worker {@link EventLoopGroup} which is responsible for performing socket I/O and running
     * {@link Service#serve(ServiceRequestContext, Request)}.
     * If not set, {@linkplain CommonPools#workerGroup() the common worker group} is used.
     *
     * @param shutdownOnStop whether to shut down the worker {@link EventLoopGroup}
     *                       when the {@link Server} stops
     */
    public ServerBuilder workerGroup(EventLoopGroup workerGroup, boolean shutdownOnStop) {
        this.workerGroup = requireNonNull(workerGroup, "workerGroup");
        shutdownWorkerGroupOnStop = shutdownOnStop;
        return this;
    }

    /**
     * Sets the maximum allowed number of open connections.
     */
    public ServerBuilder maxNumConnections(int maxNumConnections) {
        this.maxNumConnections = ServerConfig.validateMaxNumConnections(maxNumConnections);
        return this;
    }

    /**
     * Sets the idle timeout of a connection in milliseconds for keep-alive.
     *
     * @param idleTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ServerBuilder idleTimeoutMillis(long idleTimeoutMillis) {
        return idleTimeout(Duration.ofMillis(idleTimeoutMillis));
    }

    /**
     * Sets the idle timeout of a connection for keep-alive.
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
        this.defaultRequestTimeoutMillis = validateDefaultRequestTimeoutMillis(defaultRequestTimeoutMillis);
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
        this.defaultMaxRequestLength = validateDefaultMaxRequestLength(defaultMaxRequestLength);
        return this;
    }

    /**
     * Sets the maximum length of an HTTP/1 response initial line.
     */
    public ServerBuilder maxHttp1InitialLineLength(int maxHttp1InitialLineLength) {
        this.maxHttp1InitialLineLength = validateNonNegative(
                maxHttp1InitialLineLength, "maxHttp1InitialLineLength");
        return this;
    }

    /**
     * Sets the maximum length of all headers in an HTTP/1 response.
     */
    public ServerBuilder maxHttp1HeaderSize(int maxHttp1HeaderSize) {
        this.maxHttp1HeaderSize = validateNonNegative(maxHttp1HeaderSize, "maxHttp1HeaderSize");
        return this;
    }

    /**
     * Sets the maximum length of each chunk in an HTTP/1 response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     */
    public ServerBuilder maxHttp1ChunkSize(int maxHttp1ChunkSize) {
        this.maxHttp1ChunkSize = validateNonNegative(maxHttp1ChunkSize, "maxHttp1ChunkSize");
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
        gracefulShutdownQuietPeriod = validateNonNegative(quietPeriod, "quietPeriod");
        gracefulShutdownTimeout = validateNonNegative(timeout, "timeout");
        ServerConfig.validateGreaterThanOrEqual(gracefulShutdownTimeout, "quietPeriod",
                                                gracefulShutdownQuietPeriod, "timeout");
        return this;
    }

    /**
     * Sets the {@link Executor} dedicated to the execution of blocking tasks or invocations.
     * If not set, {@linkplain CommonPools#blockingTaskExecutor() the common pool} is used.
     */
    public ServerBuilder blockingTaskExecutor(Executor blockingTaskExecutor) {
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        return this;
    }

    /**
     * Sets the {@link MeterRegistry} that collects various stats.
     */
    public ServerBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
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
     * Binds the specified {@link Service} at the specified path pattern of the default {@link VirtualHost}.
     *
     * @deprecated Use {@link #service(String, Service)} instead.
     */
    @Deprecated
    public ServerBuilder serviceAt(String pathPattern, Service<HttpRequest, HttpResponse> service) {
        return service(pathPattern, service);
    }

    /**
     * Binds the specified {@link Service} under the specified directory of the default {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder serviceUnder(String pathPrefix, Service<HttpRequest, HttpResponse> service) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.serviceUnder(pathPrefix, service);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified path pattern of the default {@link VirtualHost}.
     * e.g.
     * <ul>
     *   <li>{@code /login} (no path parameters)</li>
     *   <li>{@code /users/{userId}} (curly-brace style)</li>
     *   <li>{@code /list/:productType/by/:ordering} (colon style)</li>
     *   <li>{@code exact:/foo/bar} (exact match)</li>
     *   <li>{@code prefix:/files} (prefix match)</li>
     *   <li><code>glob:/~&#42;/downloads/**</code> (glob pattern)</li>
     *   <li>{@code regex:^/files/(?<filePath>.*)$} (regular expression)</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder service(String pathPattern, Service<HttpRequest, HttpResponse> service) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(pathPattern, service);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping} of the default
     * {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder service(PathMapping pathMapping, Service<HttpRequest, HttpResponse> service) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(pathMapping, service);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping} of the default
     * {@link VirtualHost}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    public ServerBuilder service(PathMapping pathMapping, Service<HttpRequest, HttpResponse> service,
                                 String loggerName) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(pathMapping, service, loggerName);
        return this;
    }

    /**
     * Binds the specified {@link ServiceWithPathMappings} at multiple {@link PathMapping}s
     * of the default {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public <T extends ServiceWithPathMappings<HttpRequest, HttpResponse>>
    ServerBuilder service(T serviceWithPathMappings) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(serviceWithPathMappings);
        return this;
    }

    /**
     * Decorates and binds the specified {@link ServiceWithPathMappings} at multiple {@link PathMapping}s
     * of the default {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public <T extends ServiceWithPathMappings<HttpRequest, HttpResponse>,
            R extends Service<HttpRequest, HttpResponse>>
    ServerBuilder service(T serviceWithPathMappings, Function<T, R> decorator) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(serviceWithPathMappings, decorator);
        return this;
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public ServerBuilder annotatedService(Object service) {
        return annotatedService("/", service);
    }

    /**
     * Binds the specified annotated service object.
     */
    public ServerBuilder annotatedService(
            Object service,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<HttpRequest, HttpResponse>> decorator) {
        return annotatedService("/", service, decorator);
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public ServerBuilder annotatedService(Object service, Map<Class<?>, ResponseConverter> converters) {
        return annotatedService("/", service, converters);
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public ServerBuilder annotatedService(
            Object service, Map<Class<?>, ResponseConverter> converters,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<HttpRequest, HttpResponse>> decorator) {
        return annotatedService("/", service, converters, decorator);
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service) {
        return annotatedService(pathPrefix, service, ImmutableMap.of(), Function.identity());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public ServerBuilder annotatedService(
            String pathPrefix, Object service,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<HttpRequest, HttpResponse>> decorator) {
        return annotatedService(pathPrefix, service, ImmutableMap.of(), decorator);
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Map<Class<?>, ResponseConverter> converters) {
        return annotatedService(pathPrefix, service, converters, Function.identity());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public ServerBuilder annotatedService(
            String pathPrefix, Object service, Map<Class<?>, ResponseConverter> converters,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<HttpRequest, HttpResponse>> decorator) {

        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.annotatedService(pathPrefix, service, converters, decorator);
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
     * @throws IllegalStateException
     *     if other default {@link VirtualHost} builder methods have been invoked already, including:
     *     <ul>
     *       <li>{@link #sslContext(SslContext)}</li>
     *       <li>{@link #service(String, Service)}</li>
     *       <li>{@link #serviceUnder(String, Service)}</li>
     *       <li>{@link #service(PathMapping, Service)}</li>
     *     </ul>
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
     * Adds the specified {@link ServerListener}.
     */
    public ServerBuilder serverListener(ServerListener serverListener) {
        requireNonNull(serverListener, "serverListener");
        serverListeners.add(serverListener);
        return this;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>
     * specified by {@link VirtualHost}.
     *
     * @return {@link VirtualHostBuilder} for build the default virtual host
     */
    public ChainedVirtualHostBuilder withDefaultVirtualHost() {
        defaultVirtualHostBuilderUpdated();
        return defaultVirtualHostBuilder;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>
     * specified by {@link VirtualHost}.
     *
     * @param hostnamePattern virtual host name regular expression
     * @return {@link VirtualHostBuilder} for build the virtual host
     */
    public ChainedVirtualHostBuilder withVirtualHost(String hostnamePattern) {
        ChainedVirtualHostBuilder virtualHostBuilder = new ChainedVirtualHostBuilder(hostnamePattern, this);
        virtualHostBuilders.add(virtualHostBuilder);
        return virtualHostBuilder;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>
     * specified by {@link VirtualHost}.
     *
     * @param defaultHostname default hostname of this virtual host
     * @param hostnamePattern virtual host name regular expression
     * @return {@link VirtualHostBuilder} for build the virtual host
     */
    public ChainedVirtualHostBuilder withVirtualHost(String defaultHostname, String hostnamePattern) {
        ChainedVirtualHostBuilder virtualHostBuilder =
                new ChainedVirtualHostBuilder(defaultHostname, hostnamePattern, this);
        virtualHostBuilders.add(virtualHostBuilder);
        return virtualHostBuilder;
    }

    /**
     * Decorates all {@link Service}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates a {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    ServerBuilder decorator(Function<T, R> decorator) {

        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
                (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return this;
    }

    /**
     * Returns a newly-created {@link Server} based on the configuration properties set so far.
     */
    public Server build() {
        final List<ServerPort> ports =
                !this.ports.isEmpty() ? this.ports
                                      : Collections.singletonList(new ServerPort(0, HTTP));

        final VirtualHost defaultVirtualHost;
        if (this.defaultVirtualHost != null) {
            defaultVirtualHost = this.defaultVirtualHost.decorate(decorator);
        } else {
            defaultVirtualHost = defaultVirtualHostBuilder.build().decorate(decorator);
        }

        virtualHostBuilders.forEach(vhb -> virtualHosts.add(vhb.build()));

        final List<VirtualHost> virtualHosts;
        if (decorator != null) {
            virtualHosts = this.virtualHosts.stream()
                                            .map(h -> h.decorate(decorator))
                                            .collect(Collectors.toList());
        } else {
            virtualHosts = this.virtualHosts;
        }

        final Server server = new Server(new ServerConfig(
                ports, defaultVirtualHost, virtualHosts, workerGroup, shutdownWorkerGroupOnStop,
                maxNumConnections, idleTimeoutMillis, defaultRequestTimeoutMillis, defaultMaxRequestLength,
                maxHttp1InitialLineLength, maxHttp1HeaderSize, maxHttp1ChunkSize,
                gracefulShutdownQuietPeriod, gracefulShutdownTimeout, blockingTaskExecutor,
                meterRegistry, serviceLoggerPrefix));
        serverListeners.forEach(server::addListener);
        return server;
    }

    @Override
    public String toString() {
        return ServerConfig.toString(
                getClass(), ports, defaultVirtualHost, virtualHosts, workerGroup, shutdownWorkerGroupOnStop,
                maxNumConnections, idleTimeoutMillis, defaultRequestTimeoutMillis, defaultMaxRequestLength,
                maxHttp1InitialLineLength, maxHttp1HeaderSize, maxHttp1ChunkSize,
                gracefulShutdownQuietPeriod, gracefulShutdownTimeout,
                blockingTaskExecutor, meterRegistry, serviceLoggerPrefix);
    }
}
