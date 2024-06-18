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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static com.linecorp.armeria.server.DefaultServerConfig.validateGreaterThanOrEqual;
import static com.linecorp.armeria.server.DefaultServerConfig.validateIdleTimeoutMillis;
import static com.linecorp.armeria.server.DefaultServerConfig.validateMaxNumConnections;
import static com.linecorp.armeria.server.DefaultServerConfig.validateNonNegative;
import static com.linecorp.armeria.server.VirtualHost.normalizeHostnamePattern;
import static com.linecorp.armeria.server.VirtualHost.validateHostnamePattern;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.net.ssl.KeyManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.TlsSetters;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.BuiltInDependencyInjector;
import com.linecorp.armeria.internal.common.ReflectiveDependencyInjector;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.server.RouteDecoratingService;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.Mapping;
import io.netty.util.NetUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

/**
 * Builds a new {@link Server} and its {@link ServerConfig}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * // Add a port to listen
 * sb.http(8080);
 * // Add services to the default virtual host.
 * sb.service(...);
 * sb.serviceUnder(...);
 * // Build a server.
 * Server s = sb.build();
 * }</pre>
 *
 * <h2>Example 2</h2>
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * Server server =
 *     sb.http(8080) // Add a port to listen
 *       .defaultVirtualHost() // Add services to the default virtual host.
 *           .service(...)
 *           .serviceUnder(...)
 *       .and().virtualHost("*.foo.com") // Add a another virtual host.
 *           .service(...)
 *           .serviceUnder(...)
 *       .and().build(); // Build a server.
 * }</pre>
 *
 * <h2 id="no_port_specified">What happens if no HTTP(S) port is specified?</h2>
 *
 * <p>When no TCP/IP port number or local address is specified, {@link ServerBuilder} will automatically bind
 * to a random TCP/IP port assigned by the OS. It will serve HTTPS if you configured TLS (or HTTP otherwise),
 * e.g.
 *
 * <pre>{@code
 * // Build an HTTP server that runs on an ephemeral TCP/IP port.
 * Server httpServer = Server.builder()
 *                           .service(...)
 *                           .build();
 *
 * // Build an HTTPS server that runs on an ephemeral TCP/IP port.
 * Server httpsServer = Server.builder()
 *                            .tls(...)
 *                            .service(...)
 *                            .build();
 * }</pre>
 *
 * @see VirtualHostBuilder
 */
public final class ServerBuilder implements TlsSetters, ServiceConfigsBuilder<ServerBuilder> {
    private static final Logger logger = LoggerFactory.getLogger(ServerBuilder.class);

    // Defaults to no graceful shutdown.
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ZERO;
    private static final int PROXY_PROTOCOL_DEFAULT_MAX_TLV_SIZE = 65535 - 216;
    private static final String DEFAULT_ACCESS_LOGGER_PREFIX = "com.linecorp.armeria.logging.access";
    private static final Consumer<ChannelPipeline> DEFAULT_CHILD_CHANNEL_PIPELINE_CUSTOMIZER =
            v -> { /* no-op */ };

    @VisibleForTesting
    static final long MIN_PING_INTERVAL_MILLIS = 1000L;
    private static final long MIN_MAX_CONNECTION_AGE_MILLIS = 1_000L;
    private static final ExecutorService START_STOP_EXECUTOR = Executors.newSingleThreadExecutor(
            ThreadFactories.newThreadFactory("startstop-support", true));

    static {
        RequestContextUtil.init();
    }

    private final List<ServerPort> ports = new ArrayList<>();
    private final List<ServerListener> serverListeners = new ArrayList<>();
    @VisibleForTesting
    final VirtualHostBuilder virtualHostTemplate = new VirtualHostBuilder(this, false);
    private final VirtualHostBuilder defaultVirtualHostBuilder = new VirtualHostBuilder(this, true);
    private final List<VirtualHostBuilder> virtualHostBuilders = new ArrayList<>();

    EventLoopGroup workerGroup = CommonPools.workerGroup();
    private boolean shutdownWorkerGroupOnStop;
    private Executor startStopExecutor = START_STOP_EXECUTOR;
    private final Map<ChannelOption<?>, Object> channelOptions = new Object2ObjectArrayMap<>();
    private final Map<ChannelOption<?>, Object> childChannelOptions = new Object2ObjectArrayMap<>();
    private Consumer<ChannelPipeline> childChannelPipelineCustomizer =
            DEFAULT_CHILD_CHANNEL_PIPELINE_CUSTOMIZER;
    private int maxNumConnections = Flags.maxNumConnections();
    private long idleTimeoutMillis = Flags.defaultServerIdleTimeoutMillis();
    private boolean keepAliveOnPing = Flags.defaultServerKeepAliveOnPing();
    private long pingIntervalMillis = Flags.defaultPingIntervalMillis();
    private long maxConnectionAgeMillis = Flags.defaultMaxServerConnectionAgeMillis();
    private long connectionDrainDurationMicros = Flags.defaultServerConnectionDrainDurationMicros();
    private int maxNumRequestsPerConnection = Flags.defaultMaxServerNumRequestsPerConnection();
    private int http2InitialConnectionWindowSize = Flags.defaultHttp2InitialConnectionWindowSize();
    private int http2InitialStreamWindowSize = Flags.defaultHttp2InitialStreamWindowSize();
    private long http2MaxStreamsPerConnection = Flags.defaultHttp2MaxStreamsPerConnection();
    private int http2MaxFrameSize = Flags.defaultHttp2MaxFrameSize();
    private long http2MaxHeaderListSize = Flags.defaultHttp2MaxHeaderListSize();
    private int http1MaxInitialLineLength = Flags.defaultHttp1MaxInitialLineLength();
    private int http1MaxHeaderSize = Flags.defaultHttp1MaxHeaderSize();
    private int http1MaxChunkSize = Flags.defaultHttp1MaxChunkSize();
    private int proxyProtocolMaxTlvSize = PROXY_PROTOCOL_DEFAULT_MAX_TLV_SIZE;
    private Duration gracefulShutdownQuietPeriod = DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD;
    private Duration gracefulShutdownTimeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;
    private MeterRegistry meterRegistry = Flags.meterRegistry();
    @Nullable
    private ServerErrorHandler errorHandler;
    private List<ClientAddressSource> clientAddressSources = ClientAddressSource.DEFAULT_SOURCES;
    private Predicate<? super InetAddress> clientAddressTrustedProxyFilter = address -> false;
    private Predicate<? super InetAddress> clientAddressFilter = address -> true;
    private Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper =
            ProxiedAddresses::sourceAddress;
    private boolean enableServerHeader = true;
    private boolean enableDateHeader = true;
    private Http1HeaderNaming http1HeaderNaming = Http1HeaderNaming.ofDefault();
    @Nullable
    private DependencyInjector dependencyInjector;
    private Function<? super String, String> absoluteUriTransformer = Function.identity();
    private long unloggedExceptionsReportIntervalMillis =
            Flags.defaultUnloggedExceptionsReportIntervalMillis();
    private final List<ShutdownSupport> shutdownSupports = new ArrayList<>();
    private int http2MaxResetFramesPerWindow = Flags.defaultServerHttp2MaxResetFramesPerMinute();
    private int http2MaxResetFramesWindowSeconds = 60;

    ServerBuilder() {
        // Set the default host-level properties.
        virtualHostTemplate.rejectedRouteHandler(RejectedRouteHandler.WARN);
        virtualHostTemplate.defaultServiceNaming(ServiceNaming.fullTypeName());
        virtualHostTemplate.requestTimeoutMillis(Flags.defaultRequestTimeoutMillis());
        virtualHostTemplate.maxRequestLength(Flags.defaultMaxRequestLength());
        virtualHostTemplate.verboseResponses(Flags.verboseResponses());
        virtualHostTemplate.accessLogger(
                host -> LoggerFactory.getLogger(defaultAccessLoggerName(host.hostnamePattern())));
        virtualHostTemplate.tlsSelfSigned(false);
        virtualHostTemplate.tlsAllowUnsafeCiphers(false);
        virtualHostTemplate.tlsEngineType(Flags.tlsEngineType());
        virtualHostTemplate.annotatedServiceExtensions(ImmutableList.of(), ImmutableList.of(),
                                                       ImmutableList.of());
        virtualHostTemplate.blockingTaskExecutor(CommonPools.blockingTaskExecutor(), false);
        virtualHostTemplate.successFunction(SuccessFunction.ofDefault());
        virtualHostTemplate.requestAutoAbortDelayMillis(0);
        virtualHostTemplate.multipartUploadsLocation(Flags.defaultMultipartUploadsLocation());
        virtualHostTemplate.multipartRemovalStrategy(Flags.defaultMultipartRemovalStrategy());
        virtualHostTemplate.requestIdGenerator(routingContext -> RequestId.random());
    }

    private static String defaultAccessLoggerName(String hostnamePattern) {
        requireNonNull(hostnamePattern, "hostnamePattern");
        final HostAndPort hostAndPort = HostAndPort.fromString(hostnamePattern);
        hostnamePattern = hostAndPort.getHost();
        final String[] elements = hostnamePattern.split("\\.");
        final StringBuilder name = new StringBuilder(
                DEFAULT_ACCESS_LOGGER_PREFIX.length() + hostnamePattern.length() + 1);
        name.append(DEFAULT_ACCESS_LOGGER_PREFIX);
        for (int i = elements.length - 1; i >= 0; i--) {
            final String element = elements[i];
            if (element.isEmpty() || "*".equals(element)) {
                continue;
            }
            name.append('.');
            name.append(element);
        }
        if (hostAndPort.hasPort()) {
            name.append(':');
            name.append(hostAndPort.getPort());
        }
        return name.toString();
    }

    /**
     * Adds an HTTP port that listens on all available network interfaces.
     *
     * @param port the HTTP port number.
     *
     * @see #http(InetSocketAddress)
     * @see <a href="#no_port_specified">What happens if no HTTP(S) port is specified?</a>
     */
    public ServerBuilder http(int port) {
        return port(new ServerPort(port, HTTP));
    }

    /**
     * Adds an HTTP port that listens to the specified {@code localAddress}.
     *
     * @param localAddress the local address to bind
     *
     * @see #http(int)
     * @see <a href="#no_port_specified">What happens if no HTTP(S) port is specified?</a>
     */
    public ServerBuilder http(InetSocketAddress localAddress) {
        return port(new ServerPort(requireNonNull(localAddress, "localAddress"), HTTP));
    }

    /**
     * Adds an HTTPS port that listens on all available network interfaces.
     *
     * @param port the HTTPS port number.
     *
     * @see #https(InetSocketAddress)
     * @see <a href="#no_port_specified">What happens if no HTTP(S) port is specified?</a>
     */
    public ServerBuilder https(int port) {
        return port(new ServerPort(port, HTTPS));
    }

    /**
     * Adds an HTTPS port that listens to the specified {@code localAddress}.
     *
     * @param localAddress the local address to bind
     *
     * @see #http(int)
     * @see <a href="#no_port_specified">What happens if no HTTP(S) port is specified?</a>
     */
    public ServerBuilder https(InetSocketAddress localAddress) {
        return port(new ServerPort(requireNonNull(localAddress, "localAddress"), HTTPS));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}s. Specify multiple protocols to serve more than
     * one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * // Serve both HTTP and HTTPS at port 8080.
     * sb.port(8080,
     *         SessionProtocol.HTTP,
     *         SessionProtocol.HTTPS);
     * // Enable HTTPS with PROXY protocol support at port 8443.
     * sb.port(8443,
     *         SessionProtocol.PROXY,
     *         SessionProtocol.HTTPS);
     * }</pre>
     */
    public ServerBuilder port(int port, SessionProtocol... protocols) {
        return port(new ServerPort(port, protocols));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}s. Specify multiple protocols to serve more than
     * one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * // Serve both HTTP and HTTPS at port 8080.
     * sb.port(8080,
     *         Arrays.asList(SessionProtocol.HTTP,
     *                       SessionProtocol.HTTPS));
     * // Enable HTTPS with PROXY protocol support at port 8443.
     * sb.port(8443,
     *         Arrays.asList(SessionProtocol.PROXY,
     *                       SessionProtocol.HTTPS));
     * }</pre>
     */
    public ServerBuilder port(int port, Iterable<SessionProtocol> protocols) {
        return port(new ServerPort(port, protocols));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}s. Specify multiple protocols to serve more than one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * // Serve both HTTP and HTTPS at port 8080.
     * sb.port(new InetSocketAddress(8080),
     *         SessionProtocol.HTTP,
     *         SessionProtocol.HTTPS);
     * // Enable HTTPS with PROXY protocol support at port 8443.
     * sb.port(new InetSocketAddress(8443),
     *         SessionProtocol.PROXY,
     *         SessionProtocol.HTTPS);
     * }</pre>
     */
    public ServerBuilder port(InetSocketAddress localAddress, SessionProtocol... protocols) {
        return port(new ServerPort(localAddress, protocols));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}s. Specify multiple protocols to serve more than one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * // Serve both HTTP and HTTPS at port 8080.
     * sb.port(new InetSocketAddress(8080),
     *         Arrays.asList(SessionProtocol.HTTP,
     *                       SessionProtocol.HTTPS));
     * // Enable HTTPS with PROXY protocol support at port 8443.
     * sb.port(new InetSocketAddress(8443),
     *         Arrays.asList(SessionProtocol.PROXY,
     *                       SessionProtocol.HTTPS));
     * }</pre>
     */
    public ServerBuilder port(InetSocketAddress localAddress, Iterable<SessionProtocol> protocols) {
        return port(new ServerPort(localAddress, protocols));
    }

    /**
     * Adds the specified {@link ServerPort}.
     *
     * @see <a href="#no_port_specified">What happens if no HTTP(S) port is specified?</a>
     */
    public ServerBuilder port(ServerPort port) {
        ports.add(requireNonNull(port, "port"));
        return this;
    }

    /**
     * Adds a new {@link ServerPort} that listens to the loopback {@code localAddress} using the specified
     * {@link SessionProtocol}s. Specify multiple protocols to serve more than one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * sb.localPort(8080, SessionProtocol.HTTP, SessionProtocol.HTTPS);
     * }</pre>
     */
    public ServerBuilder localPort(int port, SessionProtocol... protocols) {
        requireNonNull(protocols, "protocols");
        return localPort(port, ImmutableList.copyOf(protocols));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the loopback {@code localAddress} using the specified
     * {@link SessionProtocol}s. Specify multiple protocols to serve more than one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * sb.localPort(8080, Arrays.asList(SessionProtocol.HTTP, SessionProtocol.HTTPS));
     * }</pre>
     */
    public ServerBuilder localPort(int port, Iterable<SessionProtocol> protocols) {
        final long portGroup = ServerPort.nextPortGroup();
        port(new ServerPort(new InetSocketAddress(NetUtil.LOCALHOST4, port), protocols, portGroup));

        if (SystemInfo.hasIpV6()) {
            port(new ServerPort(new InetSocketAddress(NetUtil.LOCALHOST6, port), protocols, portGroup));
        }

        return this;
    }

    /**
     * Sets the {@link ChannelOption} of the server socket bound by {@link Server}.
     * Note that the previously added option will be overridden if the same option is set again.
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * sb.channelOption(ChannelOption.BACKLOG, 1024);
     * }</pre>
     */
    public <T> ServerBuilder channelOption(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");
        checkArgument(!ChannelUtil.prohibitedOptions().contains(option),
                      "prohibited socket option: %s", option);

        option.validate(value);
        channelOptions.put(option, value);
        return this;
    }

    /**
     * Sets the {@link ChannelOption} of sockets accepted by {@link Server}.
     * Note that the previously added option will be overridden if the same option is set again.
     *
     * <pre>{@code
     * ServerBuilder sb = Server.builder();
     * sb.childChannelOption(ChannelOption.SO_REUSEADDR, true)
     *   .childChannelOption(ChannelOption.SO_KEEPALIVE, true);
     * }</pre>
     */
    public <T> ServerBuilder childChannelOption(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");
        checkArgument(!ChannelUtil.prohibitedOptions().contains(option),
                      "prohibited socket option: %s", option);

        option.validate(value);
        childChannelOptions.put(option, value);
        return this;
    }

    /**
     * (Advanced users only) Adds the {@link Consumer} that customizes the Netty {@link ChannelPipeline}.
     * This customizer is run right after the initial set of {@link ChannelHandler}s are configured.
     * This customizer is no-op by default.
     *
     * <p>Note that usage of this customizer is an advanced feature and may produce unintended side effects,
     * including complete breakdown. It is not recommended if you are not familiar with Armeria and Netty
     * internals.
     */
    @UnstableApi
    public ServerBuilder childChannelPipelineCustomizer(
            Consumer<? super ChannelPipeline> childChannelPipelineCustomizer) {
        requireNonNull(childChannelPipelineCustomizer, "childChannelPipelineCustomizer");
        this.childChannelPipelineCustomizer =
                this.childChannelPipelineCustomizer.andThen(childChannelPipelineCustomizer);
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
        // We don't use ShutdownSupport to shutdown with other instances because we shut down workerGroup first.
        shutdownWorkerGroupOnStop = shutdownOnStop;
        return this;
    }

    /**
     * Uses a newly created {@link EventLoopGroup} with the specified number of threads for
     * performing socket I/O and running {@link Service#serve(ServiceRequestContext, Request)}.
     * The worker {@link EventLoopGroup} will be shut down when the {@link Server} stops.
     *
     * @param numThreads the number of event loop threads
     */
    public ServerBuilder workerGroup(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        workerGroup(EventLoopGroups.newEventLoopGroup(numThreads), true);
        return this;
    }

    /**
     * Sets the worker {@link EventLoopGroup} which is responsible for running
     * {@link Service#serve(ServiceRequestContext, Request)}.
     * If not set, the value set via {@linkplain #workerGroup(EventLoopGroup, boolean)}
     * or {@linkplain #workerGroup(int)} is used.
     *
     * @param shutdownOnStop whether to shut down the worker {@link EventLoopGroup}
     *                       when the {@link Server} stops
     */
    @UnstableApi
    public ServerBuilder serviceWorkerGroup(EventLoopGroup serviceWorkerGroup, boolean shutdownOnStop) {
        virtualHostTemplate.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
        return this;
    }

    /**
     * Uses a newly created {@link EventLoopGroup} with the specified number of threads for
     * running {@link Service#serve(ServiceRequestContext, Request)}.
     * The worker {@link EventLoopGroup} will be shut down when the {@link Server} stops.
     *
     * @param numThreads the number of event loop threads
     */
    @UnstableApi
    public ServerBuilder serviceWorkerGroup(int numThreads) {
        virtualHostTemplate.serviceWorkerGroup(EventLoopGroups.newEventLoopGroup(numThreads), true);
        return this;
    }

    /**
     * Sets the {@link Executor} which will invoke the callbacks of {@link Server#start()},
     * {@link Server#stop()} and {@link ServerListener}.
     */
    public ServerBuilder startStopExecutor(Executor startStopExecutor) {
        this.startStopExecutor = requireNonNull(startStopExecutor, "startStopExecutor");
        return this;
    }

    /**
     * Sets the maximum allowed number of open connections.
     */
    public ServerBuilder maxNumConnections(int maxNumConnections) {
        this.maxNumConnections = validateMaxNumConnections(maxNumConnections);
        return this;
    }

    @VisibleForTesting
    int maxNumConnections() {
        return maxNumConnections;
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
     * Sets the idle timeout of a connection in milliseconds for keep-alive and whether to prevent
     * connection going idle when an HTTP/2 PING frame or {@code "OPTIONS * HTTP/1.1"} request is received.
     *
     * @param idleTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     * @param keepAliveOnPing whether to reset idle timeout on HTTP/2 PING frame, OPTIONS * request or not.
     */
    @UnstableApi
    public ServerBuilder idleTimeoutMillis(long idleTimeoutMillis, boolean keepAliveOnPing) {
        return idleTimeout(Duration.ofMillis(idleTimeoutMillis), keepAliveOnPing);
    }

    /**
     * Sets the idle timeout of a connection for keep-alive.
     *
     * @param idleTimeout the timeout. {@code 0} disables the timeout.
     */
    public ServerBuilder idleTimeout(Duration idleTimeout) {
        requireNonNull(idleTimeout, "idleTimeout");
        idleTimeoutMillis = validateIdleTimeoutMillis(idleTimeout.toMillis());
        return this;
    }

    /**
     * Sets the idle timeout of a connection for keep-alive and whether to prevent connection
     * connection going idle when an HTTP/2 PING frame or {@code "OPTIONS * HTTP/1.1"} request is received.
     *
     * @param idleTimeout the timeout. {@code 0} disables the timeout.
     * @param keepAliveOnPing whether to reset idle timeout on HTTP/2 PING frame, OPTIONS * request or not.
     */
    @UnstableApi
    public ServerBuilder idleTimeout(Duration idleTimeout, boolean keepAliveOnPing) {
        requireNonNull(idleTimeout, "idleTimeout");
        idleTimeoutMillis = validateIdleTimeoutMillis(idleTimeout.toMillis());
        this.keepAliveOnPing = keepAliveOnPing;
        return this;
    }

    /**
     * Sets the HTTP/2 <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.7">PING</a> interval.
     *
     * <p>Note that this settings is only in effect when {@link #idleTimeoutMillis(long)}} or
     * {@link #idleTimeout(Duration)} is greater than the specified PING interval.
     *
     * <p>The minimum allowed PING interval is {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     * {@code 0} means the server will not send PING frames on an HTTP/2 connection.
     *
     * @throws IllegalArgumentException if the specified {@code pingIntervalMillis} is smaller than
     *                                  {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     */
    public ServerBuilder pingIntervalMillis(long pingIntervalMillis) {
        checkArgument(pingIntervalMillis == 0 || pingIntervalMillis >= MIN_PING_INTERVAL_MILLIS,
                      "pingIntervalMillis: %s (expected: >= %s or == 0)", pingIntervalMillis,
                      MIN_PING_INTERVAL_MILLIS);
        this.pingIntervalMillis = pingIntervalMillis;
        return this;
    }

    /**
     * Sets the HTTP/2 <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.7">PING</a> interval.
     *
     * <p>Note that this settings is only in effect when {@link #idleTimeoutMillis(long)}} or
     * {@link #idleTimeout(Duration)} is greater than the specified PING interval.
     *
     * <p>The minimum allowed PING interval is {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     * {@code 0} means the server will not send PING frames on an HTTP/2 connection.
     *
     * @throws IllegalArgumentException if the specified {@code pingInterval} is smaller than
     *                                  {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     */
    public ServerBuilder pingInterval(Duration pingInterval) {
        pingIntervalMillis(requireNonNull(pingInterval, "pingInterval").toMillis());
        return this;
    }

    /**
     * Sets the maximum allowed age of a connection in millis for keep-alive. A connection is disconnected
     * after the specified {@code maxConnectionAgeMillis} since the connection was established.
     * This option is disabled by default, which means unlimited.
     *
     * @param maxConnectionAgeMillis the maximum connection age in millis. {@code 0} disables the limit.
     * @throws IllegalArgumentException if the specified {@code maxConnectionAgeMillis} is smaller than
     *                                  {@value #MIN_MAX_CONNECTION_AGE_MILLIS} milliseconds.
     */
    public ServerBuilder maxConnectionAgeMillis(long maxConnectionAgeMillis) {
        checkArgument(maxConnectionAgeMillis >= MIN_MAX_CONNECTION_AGE_MILLIS || maxConnectionAgeMillis == 0,
                      "maxConnectionAgeMillis: %s (expected: >= %s or == 0)",
                      maxConnectionAgeMillis, MIN_MAX_CONNECTION_AGE_MILLIS);
        this.maxConnectionAgeMillis = maxConnectionAgeMillis;
        return this;
    }

    /**
     * Sets the maximum allowed age of a connection for keep-alive. A connection is disconnected
     * after the specified {@code maxConnectionAge} since the connection was established.
     * This option is disabled by default, which means unlimited.
     *
     * @param maxConnectionAge the maximum connection age. {@code 0} disables the limit.
     * @throws IllegalArgumentException if the specified {@code maxConnectionAge} is smaller than
     *                                  {@value #MIN_MAX_CONNECTION_AGE_MILLIS} milliseconds.
     */
    public ServerBuilder maxConnectionAge(Duration maxConnectionAge) {
        return maxConnectionAgeMillis(requireNonNull(maxConnectionAge, "maxConnectionAge").toMillis());
    }

    /**
     * Sets the connection drain duration in micros for the connection shutdown.
     * At the beginning of the connection drain server signals the clients that the connection shutdown is
     * imminent but still accepts in flight requests.
     * After the connection drain end server stops accepting new requests.
     * Also, see {@link ServerBuilder#connectionDrainDuration(Duration)}.
     *
     * <p>
     * Note that HTTP/1 doesn't support draining as described here, so for HTTP/1 drain duration
     * is always {@code 0}, which means the connection will be closed immediately as soon as
     * the current in-progress request is handled.
     * </p>
     *
     * @param durationMicros the drain duration. {@code 0} disables the drain.
     */
    public ServerBuilder connectionDrainDurationMicros(long durationMicros) {
        checkArgument(connectionDrainDurationMicros >= 0,
                      "connectionDrainDurationMicros: %s (expected: >= 0)",
                      connectionDrainDurationMicros);
        connectionDrainDurationMicros = durationMicros;
        return this;
    }

    /**
     * Sets the connection drain duration in micros for the connection shutdown.
     * At the beginning of the connection drain server signals the clients that the connection shutdown is
     * imminent but still accepts in flight requests.
     * After the connection drain end server stops accepting new requests.
     * Also, see {@link ServerBuilder#connectionDrainDurationMicros(long)}.
     *
     * <p>
     * Note that HTTP/1 doesn't support draining as described here, so for HTTP/1 drain duration
     * is always {@code 0}.
     * </p>
     *
     * @param duration the drain period. {@code Duration.ZERO} or negative value disables the drain period.
     */
    public ServerBuilder connectionDrainDuration(Duration duration) {
        requireNonNull(duration, "duration");
        return connectionDrainDurationMicros(TimeUnit.NANOSECONDS.toMicros(duration.toNanos()));
    }

    /**
     * Sets the maximum allowed number of requests that can be served through one connection.
     * This option is disabled by default, which means unlimited.
     *
     * @param maxNumRequestsPerConnection the maximum number of requests per connection.
     *                                    {@code 0} disables the limit.
     */
    public ServerBuilder maxNumRequestsPerConnection(int maxNumRequestsPerConnection) {
        this.maxNumRequestsPerConnection =
                validateNonNegative(maxNumRequestsPerConnection, "maxNumRequestsPerConnection");
        return this;
    }

    /**
     * Sets the initial connection-level HTTP/2 flow control window size. Larger values can lower stream
     * warmup time at the expense of being easier to overload the server. Defaults to
     * {@link Flags#defaultHttp2InitialConnectionWindowSize()}.
     * Note that this setting affects the connection-level window size, not the window size of streams.
     *
     * @see #http2InitialStreamWindowSize(int)
     */
    public ServerBuilder http2InitialConnectionWindowSize(int http2InitialConnectionWindowSize) {
        checkArgument(http2InitialConnectionWindowSize > 0,
                      "http2InitialConnectionWindowSize: %s (expected: > 0)",
                      http2InitialConnectionWindowSize);
        this.http2InitialConnectionWindowSize = http2InitialConnectionWindowSize;
        return this;
    }

    /**
     * Sets the initial stream-level HTTP/2 flow control window size. Larger values can lower stream
     * warmup time at the expense of being easier to overload the server. Defaults to
     * {@link Flags#defaultHttp2InitialStreamWindowSize()}.
     * Note that this setting affects the stream-level window size, not the window size of connections.
     *
     * @see #http2InitialConnectionWindowSize(int)
     */
    public ServerBuilder http2InitialStreamWindowSize(int http2InitialStreamWindowSize) {
        checkArgument(http2InitialStreamWindowSize > 0,
                      "http2InitialStreamWindowSize: %s (expected: > 0)",
                      http2InitialStreamWindowSize);
        this.http2InitialStreamWindowSize = http2InitialStreamWindowSize;
        return this;
    }

    /**
     * Sets the maximum number of concurrent streams per HTTP/2 connection. Unset means there is
     * no limit on the number of concurrent streams. Note, this differs from {@link #maxNumConnections()},
     * which is the maximum number of HTTP/2 connections themselves, not the streams that are
     * multiplexed over each.
     */
    public ServerBuilder http2MaxStreamsPerConnection(long http2MaxStreamsPerConnection) {
        checkArgument(http2MaxStreamsPerConnection > 0 &&
                      http2MaxStreamsPerConnection <= 0xFFFFFFFFL,
                      "http2MaxStreamsPerConnection: %s (expected: a positive 32-bit unsigned integer)",
                      http2MaxStreamsPerConnection);
        this.http2MaxStreamsPerConnection = http2MaxStreamsPerConnection;
        return this;
    }

    /**
     * Sets the maximum number of RST frames that are allowed per window before the connection is closed. This
     * allows to protect against the remote peer flooding us with such frames and using up a lot of CPU.
     * Defaults to {@link Flags#defaultServerHttp2MaxResetFramesPerMinute()}.
     *
     * <p>Note that {@code 0} for any of the parameters means no protection should be applied.
     */
    @UnstableApi
    public ServerBuilder http2MaxResetFramesPerWindow(int http2MaxResetFramesPerWindow,
                                                      int http2MaxResetFramesWindowSeconds) {
        checkArgument(http2MaxResetFramesPerWindow >= 0, "http2MaxResetFramesPerWindow: %s (expected: >= 0)",
                      http2MaxResetFramesPerWindow);
        checkArgument(http2MaxResetFramesWindowSeconds >= 0,
                      "http2MaxResetFramesWindowSeconds: %s (expected: >= 0)",
                      http2MaxResetFramesWindowSeconds);
        this.http2MaxResetFramesPerWindow = http2MaxResetFramesPerWindow;
        this.http2MaxResetFramesWindowSeconds = http2MaxResetFramesWindowSeconds;
        return this;
    }

    /**
     * Sets the maximum size of HTTP/2 frame that can be received. Defaults to
     * {@link Flags#defaultHttp2MaxFrameSize()}.
     */
    public ServerBuilder http2MaxFrameSize(int http2MaxFrameSize) {
        checkArgument(http2MaxFrameSize >= MAX_FRAME_SIZE_LOWER_BOUND &&
                      http2MaxFrameSize <= MAX_FRAME_SIZE_UPPER_BOUND,
                      "http2MaxFrameSize: %s (expected: >= %s and <= %s)",
                      http2MaxFrameSize, MAX_FRAME_SIZE_LOWER_BOUND, MAX_FRAME_SIZE_UPPER_BOUND);
        this.http2MaxFrameSize = http2MaxFrameSize;
        return this;
    }

    /**
     * Sets the maximum size of headers that can be received. Defaults to
     * {@link Flags#defaultHttp2MaxHeaderListSize()}.
     */
    public ServerBuilder http2MaxHeaderListSize(long http2MaxHeaderListSize) {
        checkArgument(http2MaxHeaderListSize > 0 &&
                      http2MaxHeaderListSize <= 0xFFFFFFFFL,
                      "http2MaxHeaderListSize: %s (expected: a positive 32-bit unsigned integer)",
                      http2MaxHeaderListSize);
        this.http2MaxHeaderListSize = http2MaxHeaderListSize;
        return this;
    }

    /**
     * Sets the maximum length of an HTTP/1 response initial line.
     */
    public ServerBuilder http1MaxInitialLineLength(int http1MaxInitialLineLength) {
        this.http1MaxInitialLineLength = validateNonNegative(
                http1MaxInitialLineLength, "http1MaxInitialLineLength");
        return this;
    }

    /**
     * Sets the maximum length of all headers in an HTTP/1 response.
     */
    public ServerBuilder http1MaxHeaderSize(int http1MaxHeaderSize) {
        this.http1MaxHeaderSize = validateNonNegative(http1MaxHeaderSize, "http1MaxHeaderSize");
        return this;
    }

    /**
     * Sets the maximum length of each chunk in an HTTP/1 response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     */
    public ServerBuilder http1MaxChunkSize(int http1MaxChunkSize) {
        this.http1MaxChunkSize = validateNonNegative(http1MaxChunkSize, "http1MaxChunkSize");
        return this;
    }

    /**
     * Sets the amount of time to wait after calling {@link Server#stop()} for
     * requests to go away before actually shutting down.
     *
     * @param quietPeriodMillis the number of milliseconds to wait for active
     *                          requests to go end before shutting down. 0 means the server will
     *                          stop right away without waiting.
     * @param timeoutMillis the number of milliseconds to wait before shutting down the server regardless of
     *                      active requests. This should be set to a time greater than {@code quietPeriodMillis}
     *                      to ensure the server shuts down even if there is a stuck request.
     */
    public ServerBuilder gracefulShutdownTimeoutMillis(long quietPeriodMillis, long timeoutMillis) {
        return gracefulShutdownTimeout(
                Duration.ofMillis(quietPeriodMillis), Duration.ofMillis(timeoutMillis));
    }

    /**
     * Sets the amount of time to wait after calling {@link Server#stop()} for
     * requests to go away before actually shutting down.
     *
     * @param quietPeriod the number of milliseconds to wait for active
     *                    requests to go end before shutting down. {@link Duration#ZERO} means
     *                    the server will stop right away without waiting.
     * @param timeout the amount of time to wait before shutting down the server regardless of active requests.
     *                This should be set to a time greater than {@code quietPeriod} to ensure the server
     *                shuts down even if there is a stuck request.
     */
    public ServerBuilder gracefulShutdownTimeout(Duration quietPeriod, Duration timeout) {
        requireNonNull(quietPeriod, "quietPeriod");
        requireNonNull(timeout, "timeout");
        gracefulShutdownQuietPeriod = validateNonNegative(quietPeriod, "quietPeriod");
        gracefulShutdownTimeout = validateNonNegative(timeout, "timeout");
        validateGreaterThanOrEqual(gracefulShutdownTimeout, "quietPeriod",
                                   gracefulShutdownQuietPeriod, "timeout");
        return this;
    }

    /**
     * Sets the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to receive additional data even after closing the response.
     * Specify {@link Duration#ZERO} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    public ServerBuilder requestAutoAbortDelay(Duration delay) {
        return requestAutoAbortDelayMillis(requireNonNull(delay, "delay").toMillis());
    }

    /**
     * Sets the amount of time in millis to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to receive additional data even after closing the response.
     * Specify {@code 0} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    public ServerBuilder requestAutoAbortDelayMillis(long delayMillis) {
        virtualHostTemplate.requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    /**
     * Sets the {@link Path} for storing upload file through multipart/form-data.
     *
     * @param path the path of the directory stores the file.
     */
    @UnstableApi
    public ServerBuilder multipartUploadsLocation(Path path) {
        requireNonNull(path, "path");
        virtualHostTemplate.multipartUploadsLocation(path);
        return this;
    }

    /**
     * Sets the {@link MultipartRemovalStrategy} that determines when to remove temporary files created
     * for multipart requests.
     * If not set, {@link MultipartRemovalStrategy#ON_RESPONSE_COMPLETION} is used by default.
     */
    @UnstableApi
    public ServerBuilder multipartRemovalStrategy(MultipartRemovalStrategy removalStrategy) {
        requireNonNull(removalStrategy, "removalStrategy");
        virtualHostTemplate.multipartRemovalStrategy(removalStrategy);
        return this;
    }

    /**
     * Sets the {@link ScheduledExecutorService} dedicated to the execution of blocking tasks or invocations.
     * If not set, {@linkplain CommonPools#blockingTaskExecutor() the common pool} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link ScheduledExecutorService} when the
     *                       {@link Server} stops
     */
    public ServerBuilder blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                              boolean shutdownOnStop) {
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        virtualHostTemplate.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    /**
     * Sets the {@link BlockingTaskExecutor} dedicated to the execution of blocking tasks or invocations.
     * If not set, {@linkplain CommonPools#blockingTaskExecutor() the common pool} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link BlockingTaskExecutor} when the
     *                       {@link Server} stops
     */
    public ServerBuilder blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                              boolean shutdownOnStop) {
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        virtualHostTemplate.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    /**
     * Uses a newly created {@link BlockingTaskExecutor} with the specified number of threads dedicated to
     * the execution of blocking tasks or invocations.
     * The {@link BlockingTaskExecutor} will be shut down when the {@link Server} stops.
     *
     * @param numThreads the number of threads in the executor
     */
    public ServerBuilder blockingTaskExecutor(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        final BlockingTaskExecutor executor = BlockingTaskExecutor.builder()
                                                                  .numThreads(numThreads)
                                                                  .build();
        return blockingTaskExecutor(executor, true);
    }

    /**
     * Sets a {@link SuccessFunction} that determines whether a request was handled successfully or not.
     * If unspecified, {@link SuccessFunction#ofDefault()} is used.
     */
    @UnstableApi
    public ServerBuilder successFunction(SuccessFunction successFunction) {
        virtualHostTemplate.successFunction(requireNonNull(successFunction, "successFunction"));
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
     * Sets a global naming rule for the name of services. This property can be overridden via
     * {@link VirtualHostBuilder#defaultServiceNaming(ServiceNaming)}. The overriding is also possible if
     * service-level naming rule is set via {@link ServiceBindingBuilder#defaultServiceNaming(ServiceNaming)}.
     *
     * @see RequestOnlyLog#serviceName()
     */
    public ServerBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        virtualHostTemplate.defaultServiceNaming(defaultServiceNaming);
        return this;
    }

    /**
     * Sets the format of this {@link Server}'s access log. The specified {@code accessLogFormat} would be
     * parsed by {@link AccessLogWriter#custom(String)}.
     */
    public ServerBuilder accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               false);
    }

    /**
     * Sets an access log writer of this {@link Server}. {@link AccessLogWriter#disabled()} is used by default.
     *
     * @param shutdownOnStop whether to shut down the {@link AccessLogWriter} when the {@link Server} stops
     */
    public ServerBuilder accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        virtualHostTemplate.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    /**
     * Sets the maximum size of additional data for PROXY protocol. The default value of this property is
     * {@value #PROXY_PROTOCOL_DEFAULT_MAX_TLV_SIZE}.
     *
     * <p><b>Note:</b> limiting TLV size only affects processing of v2, binary headers. Also, as allowed by
     * the 1.5 spec, TLV data is currently ignored. For maximum performance, it would be best to configure
     * your upstream proxy host to <b>NOT</b> send TLV data and set this property to {@code 0}.
     */
    public ServerBuilder proxyProtocolMaxTlvSize(int proxyProtocolMaxTlvSize) {
        checkArgument(proxyProtocolMaxTlvSize >= 0,
                      "proxyProtocolMaxTlvSize: %s (expected: >= 0)", proxyProtocolMaxTlvSize);
        this.proxyProtocolMaxTlvSize = proxyProtocolMaxTlvSize;
        return this;
    }

    @Override
    public ServerBuilder tls(File keyCertChainFile, File keyFile) {
        return (ServerBuilder) TlsSetters.super.tls(keyCertChainFile, keyFile);
    }

    @Override
    public ServerBuilder tls(
            File keyCertChainFile, File keyFile, @Nullable String keyPassword) {
        virtualHostTemplate.tls(keyCertChainFile, keyFile, keyPassword);
        return this;
    }

    @Override
    public ServerBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        return (ServerBuilder) TlsSetters.super.tls(keyCertChainInputStream, keyInputStream);
    }

    @Override
    public ServerBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream,
                             @Nullable String keyPassword) {
        virtualHostTemplate.tls(keyCertChainInputStream, keyInputStream, keyPassword);
        return this;
    }

    @Override
    public ServerBuilder tls(PrivateKey key, X509Certificate... keyCertChain) {
        return (ServerBuilder) TlsSetters.super.tls(key, keyCertChain);
    }

    @Override
    public ServerBuilder tls(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
        return (ServerBuilder) TlsSetters.super.tls(key, keyCertChain);
    }

    @Override
    public ServerBuilder tls(PrivateKey key, @Nullable String keyPassword, X509Certificate... keyCertChain) {
        return (ServerBuilder) TlsSetters.super.tls(key, keyPassword, keyCertChain);
    }

    @Override
    public ServerBuilder tls(PrivateKey key, @Nullable String keyPassword,
                             Iterable<? extends X509Certificate> keyCertChain) {
        virtualHostTemplate.tls(key, keyPassword, keyCertChain);
        return this;
    }

    @Override
    public ServerBuilder tls(KeyManagerFactory keyManagerFactory) {
        virtualHostTemplate.tls(keyManagerFactory);
        return this;
    }

    /**
     * Configures SSL or TLS of the {@link Server} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tlsSelfSigned() {
        virtualHostTemplate.tlsSelfSigned();
        return this;
    }

    /**
     * Configures SSL or TLS of the {@link Server} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tlsSelfSigned(boolean tlsSelfSigned) {
        virtualHostTemplate.tlsSelfSigned(tlsSelfSigned);
        return this;
    }

    @Override
    public ServerBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        virtualHostTemplate.tlsCustomizer(tlsCustomizer);
        return this;
    }

    /**
     * Allows the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information. This option is disabled by default.
     *
     * @deprecated It's not recommended to enable this option. Use it only when you have no other way to
     *             communicate with an insecure peer than this.
     */
    @Deprecated
    public ServerBuilder tlsAllowUnsafeCiphers() {
        virtualHostTemplate.tlsAllowUnsafeCiphers();
        return this;
    }

    /**
     * Allows the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information. This option is disabled by default.
     *
     * @param tlsAllowUnsafeCiphers Whether to allow the unsafe ciphers
     *
     * @deprecated It's not recommended to enable this option. Use it only when you have no other way to
     *             communicate with an insecure peer than this.
     */
    @Deprecated
    public ServerBuilder tlsAllowUnsafeCiphers(boolean tlsAllowUnsafeCiphers) {
        virtualHostTemplate.tlsAllowUnsafeCiphers(tlsAllowUnsafeCiphers);
        return this;
    }

    /**
     * Sets {@link TlsEngineType} that will be used for processing TLS connections.
     *
     * @param tlsEngineType the {@link TlsEngineType} to use
     */
    @UnstableApi
    public ServerBuilder tlsEngineType(TlsEngineType tlsEngineType) {
        virtualHostTemplate.tlsEngineType(tlsEngineType);
        return this;
    }

    /**
     * Returns a {@link ContextPathServicesBuilder} which binds {@link HttpService}s under the
     * specified context paths.
     *
     * @see ContextPathServicesBuilder
     */
    @UnstableApi
    public ContextPathServicesBuilder contextPath(String... contextPaths) {
        return contextPath(ImmutableSet.copyOf(requireNonNull(contextPaths, "contextPaths")));
    }

    /**
     * Returns a {@link ContextPathServicesBuilder} which binds {@link HttpService}s under the
     * specified context paths.
     *
     * @see ContextPathServicesBuilder
     */
    @UnstableApi
    public ContextPathServicesBuilder contextPath(Iterable<String> contextPaths) {
        requireNonNull(contextPaths, "contextPaths");
        return new ContextPathServicesBuilder(
                this, defaultVirtualHostBuilder, ImmutableSet.copyOf(contextPaths));
    }

    /**
     * Configures an {@link HttpService} of the default {@link VirtualHost} with the {@code customizer}.
     */
    public ServerBuilder withRoute(Consumer<? super ServiceBindingBuilder> customizer) {
        requireNonNull(customizer, "customizer");
        final ServiceBindingBuilder serviceBindingBuilder = new ServiceBindingBuilder(this);
        customizer.accept(serviceBindingBuilder);
        return this;
    }

    /**
     * Returns a {@link ServiceBindingBuilder} which is for binding an {@link HttpService} fluently.
     */
    @Override
    public ServiceBindingBuilder route() {
        return new ServiceBindingBuilder(this);
    }

    /**
     * Returns a {@link DecoratingServiceBindingBuilder} which is for binding a {@code decorator} fluently.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public DecoratingServiceBindingBuilder routeDecorator() {
        return new DecoratingServiceBindingBuilder(this);
    }

    /**
     * Binds the specified {@link HttpService} under the specified directory of the default {@link VirtualHost}.
     * If the specified {@link HttpService} is an {@link HttpServiceWithRoutes}, the {@code pathPrefix} is added
     * to each {@link Route} of {@link HttpServiceWithRoutes#routes()}. For example, the
     * {@code serviceWithRoutes} in the following code will be bound to
     * ({@code "/foo/bar"}) and ({@code "/foo/baz"}):
     * <pre>{@code
     * > HttpServiceWithRoutes serviceWithRoutes = new HttpServiceWithRoutes() {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) { ... }
     * >
     * >     @Override
     * >     public Set<Route> routes() {
     * >         return Set.of(Route.builder().path("/bar").build(),
     * >                       Route.builder().path("/baz").build());
     * >     }
     * > };
     * >
     * > Server.builder()
     * >       .serviceUnder("/foo", serviceWithRoutes)
     * >       .build();
     * }</pre>
     */
    @Override
    public ServerBuilder serviceUnder(String pathPrefix, HttpService service) {
        virtualHostTemplate.serviceUnder(pathPrefix, service);
        return this;
    }

    /**
     * Binds the specified {@link HttpService} at the specified path pattern of the default {@link VirtualHost}.
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
     */
    @Override
    public ServerBuilder service(String pathPattern, HttpService service) {
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(service, "service");
        warnIfServiceHasMultipleRoutes(pathPattern, service);
        virtualHostTemplate.service(pathPattern, service);
        return this;
    }

    /**
     * Binds the specified {@link HttpService} at the specified {@link Route} of the default
     * {@link VirtualHost}.
     */
    @Override
    public ServerBuilder service(Route route, HttpService service) {
        requireNonNull(route, "route");
        requireNonNull(service, "service");
        warnIfServiceHasMultipleRoutes(route.patternString(), service);
        virtualHostTemplate.service(route, service);
        return this;
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @Override
    public ServerBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        virtualHostTemplate.service(serviceWithRoutes, decorators);
        return this;
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @Override
    @SafeVarargs
    public final ServerBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Function<? super HttpService, ? extends HttpService>... decorators) {
        virtualHostTemplate.service(serviceWithRoutes, decorators);
        return this;
    }

    static HttpService decorate(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        HttpService decorated = serviceWithRoutes;
        for (Function<? super HttpService, ? extends HttpService> d : decorators) {
            checkNotNull(d, "decorators contains null: %s", decorators);
            decorated = d.apply(decorated);
            checkNotNull(decorated, "A decorator returned null: %s", d);
        }
        return decorated;
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    @Override
    public ServerBuilder annotatedService(Object service) {
        virtualHostTemplate.annotatedService(service);
        return this;
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public ServerBuilder annotatedService(Object service,
                                          Object... exceptionHandlersAndConverters) {
        virtualHostTemplate.annotatedService(service, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public ServerBuilder annotatedService(Object service,
                                          Function<? super HttpService, ? extends HttpService> decorator,
                                          Object... exceptionHandlersAndConverters) {
        virtualHostTemplate.annotatedService(service, decorator, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    @Override
    public ServerBuilder annotatedService(String pathPrefix, Object service) {
        virtualHostTemplate.annotatedService(pathPrefix, service);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Object... exceptionHandlersAndConverters) {
        virtualHostTemplate.annotatedService(pathPrefix, service, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Function<? super HttpService, ? extends HttpService> decorator,
                                          Object... exceptionHandlersAndConverters) {
        virtualHostTemplate.annotatedService(pathPrefix, service, decorator, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Iterable<?> exceptionHandlersAndConverters) {
        virtualHostTemplate.annotatedService(pathPrefix, service, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction},
     *                                       the {@link RequestConverterFunction} and/or
     *                                       the {@link ResponseConverterFunction}
     */
    @Override
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Function<? super HttpService, ? extends HttpService> decorator,
                                          Iterable<?> exceptionHandlersAndConverters) {
        virtualHostTemplate.annotatedService(pathPrefix, service, decorator, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlerFunctions the {@link ExceptionHandlerFunction}s
     * @param requestConverterFunctions the {@link RequestConverterFunction}s
     * @param responseConverterFunctions the {@link ResponseConverterFunction}s
     */
    @Override
    public ServerBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        virtualHostTemplate.annotatedService(pathPrefix, service, decorator, exceptionHandlerFunctions,
                                             requestConverterFunctions, responseConverterFunctions);
        return this;
    }

    /**
     * Returns an {@link AnnotatedServiceBindingBuilder} to build annotated service.
     */
    @Override
    public AnnotatedServiceBindingBuilder annotatedService() {
        return new AnnotatedServiceBindingBuilder(this);
    }

    ServerBuilder serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        virtualHostTemplate.addServiceConfigSetters(serviceConfigBuilder);
        return this;
    }

    ServerBuilder annotatedServiceBindingBuilder(
            AnnotatedServiceBindingBuilder annotatedServiceBindingBuilder) {
        virtualHostTemplate.addServiceConfigSetters(annotatedServiceBindingBuilder);
        return this;
    }

    ServerBuilder routingDecorator(RouteDecoratingService routeDecoratingService) {
        virtualHostTemplate.addRouteDecoratingService(routeDecoratingService);
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
     * Sets the default hostname of the default {@link VirtualHostBuilder}.
     */
    public ServerBuilder defaultHostname(String defaultHostname) {
        defaultVirtualHostBuilder.defaultHostname(defaultHostname);
        return this;
    }

    /**
     * Sets the base context path for this {@link ServerBuilder}. Services and decorators added to this
     * {@link ServerBuilder} will be prefixed by the specified {@code baseContextPath}. If a service is bound
     * to a scoped {@link #contextPath(String...)}, the {@code baseContextPath} will be prepended to the
     * {@code contextPath}.
     *
     * <pre>{@code
     * Server
     *   .builder()
     *   .baseContextPath("/api")
     *   // The following service will be served at '/api/v1/items'.
     *   .service("/v1/items", itemService)
     *   .contextPath("/v2")
     *   // The following service will be served at '/api/v2/users'.
     *   .service("/users", usersService)
     *   .and() // end of the "/v2" contextPath
     *   .build();
     * }
     * </pre>
     *
     * <p>Note that the {@code baseContextPath} won't be applied to {@link VirtualHost}s
     * added to this {@link Server}. To configure the context path for individual
     * {@link VirtualHost}s, use {@link VirtualHostBuilder#baseContextPath(String)} instead.
     */
    public ServerBuilder baseContextPath(String baseContextPath) {
        defaultVirtualHostBuilder.baseContextPath(baseContextPath);
        return this;
    }

    /**
     * Configures the default {@link VirtualHost} with the {@code customizer}.
     */
    public ServerBuilder withDefaultVirtualHost(Consumer<? super VirtualHostBuilder> customizer) {
        customizer.accept(defaultVirtualHostBuilder);
        return this;
    }

    /**
     * Returns the {@link VirtualHostBuilder} for building the default
     * <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
     */
    public VirtualHostBuilder defaultVirtualHost() {
        return defaultVirtualHostBuilder;
    }

    /**
     * Configures a {@link VirtualHost} with the {@code customizer}.
     *
     * @deprecated Use {@link #withVirtualHost(String, Consumer)} instead.
     */
    @Deprecated
    public ServerBuilder withVirtualHost(Consumer<? super VirtualHostBuilder> customizer) {
        final VirtualHostBuilder virtualHostBuilder = new VirtualHostBuilder(this, false);
        customizer.accept(virtualHostBuilder);
        virtualHostBuilders.add(virtualHostBuilder);
        return this;
    }

    /**
     * Configures a {@link VirtualHost} with the {@code customizer}.
     */
    public ServerBuilder withVirtualHost(String hostnamePattern,
                                         Consumer<? super VirtualHostBuilder> customizer) {
        final VirtualHostBuilder virtualHostBuilder = findOrCreateVirtualHostBuilder(hostnamePattern);
        requireNonNull(customizer, "customizer");
        customizer.accept(virtualHostBuilder);
        virtualHostBuilders.add(virtualHostBuilder);
        return this;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
     *
     * @param hostnamePattern virtual host name regular expression
     * @return {@link VirtualHostBuilder} for building the virtual host
     */
    public VirtualHostBuilder virtualHost(String hostnamePattern) {
        final VirtualHostBuilder virtualHostBuilder = findOrCreateVirtualHostBuilder(hostnamePattern);
        virtualHostBuilders.add(virtualHostBuilder);
        return virtualHostBuilder;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
     *
     * @param defaultHostname default hostname of this virtual host
     * @param hostnamePattern virtual host name regular expression
     * @return {@link VirtualHostBuilder} for building the virtual host
     *
     * @deprecated prefer {@link #virtualHost(String)} with {@link VirtualHostBuilder#defaultHostname(String)}.
     */
    @Deprecated
    public VirtualHostBuilder virtualHost(String defaultHostname, String hostnamePattern) {
        final VirtualHostBuilder virtualHostBuilder = new VirtualHostBuilder(this, false)
                .defaultHostname(defaultHostname)
                .hostnamePattern(hostnamePattern);
        virtualHostBuilders.add(virtualHostBuilder);
        return virtualHostBuilder;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Port-based">port-based virtual host</a>
     * with the specified {@code port}. The returned virtual host will have a catch-all (wildcard host) name
     * pattern that allows all host names.
     *
     * <p>Note that you cannot configure TLS to the port-based virtual host. Configure it to the
     * {@link ServerBuilder} or a {@linkplain #virtualHost(String) name-based virtual host}.
     *
     * @param port the port number that this virtual host binds to
     * @return {@link VirtualHostBuilder} for building the virtual host
     */
    public VirtualHostBuilder virtualHost(int port) {
        checkArgument(port >= 1 && port <= 65535, "port: %s (expected: 1-65535)", port);

        // Look for a virtual host that has already been made and reuse it.
        final Optional<VirtualHostBuilder> vhost =
                virtualHostBuilders.stream()
                                   .filter(v -> v.port() == port && v.defaultVirtualHost())
                                   .findFirst();
        if (vhost.isPresent()) {
            return vhost.get();
        }

        final VirtualHostBuilder virtualHostBuilder = new VirtualHostBuilder(this, port);
        virtualHostBuilders.add(virtualHostBuilder);
        return virtualHostBuilder;
    }

    private VirtualHostBuilder findOrCreateVirtualHostBuilder(String hostnamePattern) {
        requireNonNull(hostnamePattern, "hostnamePattern");
        final HostAndPort hostAndPort = HostAndPort.fromString(hostnamePattern);
        validateHostnamePattern(hostAndPort.getHost());

        final String normalizedHostnamePattern = normalizeHostnamePattern(hostAndPort.getHost());
        final int port = hostAndPort.getPortOrDefault(-1);
        for (VirtualHostBuilder virtualHostBuilder : virtualHostBuilders) {
            if (!virtualHostBuilder.defaultVirtualHost() &&
                virtualHostBuilder.equalsHostnamePattern(normalizedHostnamePattern, port)) {
                return virtualHostBuilder;
            }
        }
        return new VirtualHostBuilder(this, false).hostnamePattern(normalizedHostnamePattern, port);
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@code decorator}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}s
     */
    @Override
    public ServerBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        virtualHostTemplate.decorator(decorator);
        return this;
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@link DecoratingHttpServiceFunction}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public ServerBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        virtualHostTemplate.decorator(decoratingHttpServiceFunction);
        return this;
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public ServerBuilder decorator(
            String pathPattern, Function<? super HttpService, ? extends HttpService> decorator) {
        virtualHostTemplate.decorator(pathPattern, decorator);
        return this;
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}.
     */
    @Override
    public ServerBuilder decorator(
            String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        virtualHostTemplate.decorator(pathPattern, decoratingHttpServiceFunction);
        return this;
    }

    /**
     * Decorates {@link HttpService}s with the specified {@link Route}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param route the route being decorated
     * @param decorator the {@link Function} that decorates {@link HttpService} which matches
     *                  the specified {@link Route}
     */
    @Override
    public ServerBuilder decorator(
            Route route, Function<? super HttpService, ? extends HttpService> decorator) {
        virtualHostTemplate.decorator(route, decorator);
        return this;
    }

    /**
     * Decorates {@link HttpService}s with the specified {@link Route}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param route the route being decorated
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public ServerBuilder decorator(
            Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        virtualHostTemplate.decorator(route, decoratingHttpServiceFunction);
        return this;
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public ServerBuilder decoratorUnder(
            String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        virtualHostTemplate.decoratorUnder(prefix, decoratingHttpServiceFunction);
        return this;
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public ServerBuilder decoratorUnder(String prefix,
                                        Function<? super HttpService, ? extends HttpService> decorator) {
        virtualHostTemplate.decoratorUnder(prefix, decorator);
        return this;
    }

    /**
     * Adds the {@link ServerErrorHandler} that provides the error responses in case of unexpected exceptions
     * or protocol errors. If multiple handlers are added, the latter is composed with the former using
     * {@link ServerErrorHandler#orElse(ServerErrorHandler)}.
     *
     * <p>Note that the {@link HttpResponseException} is not handled by the {@link ServerErrorHandler}
     * but the {@link HttpResponseException#httpResponse()} is sent as-is.
     */
    @UnstableApi
    public ServerBuilder errorHandler(ServerErrorHandler errorHandler) {
        requireNonNull(errorHandler, "errorHandler");
        if (this.errorHandler == null) {
            this.errorHandler = errorHandler;
        } else {
            this.errorHandler = this.errorHandler.orElse(errorHandler);
        }
        return this;
    }

    /**
     * Sets a list of {@link ClientAddressSource}s which are used to determine where to look for the
     * client address, in the order of preference. {@code Forwarded} header, {@code X-Forwarded-For} header
     * and the source address of a PROXY protocol header will be used by default.
     */
    public ServerBuilder clientAddressSources(ClientAddressSource... clientAddressSources) {
        this.clientAddressSources = ImmutableList.copyOf(
                requireNonNull(clientAddressSources, "clientAddressSources"));
        return this;
    }

    /**
     * Sets a list of {@link ClientAddressSource}s which are used to determine where to look for the
     * client address, in the order of preference. {@code Forwarded} header, {@code X-Forwarded-For} header
     * and the source address of a PROXY protocol header will be used by default.
     */
    public ServerBuilder clientAddressSources(Iterable<ClientAddressSource> clientAddressSources) {
        this.clientAddressSources = ImmutableList.copyOf(
                requireNonNull(clientAddressSources, "clientAddressSources"));
        return this;
    }

    /**
     * Sets a filter which evaluates whether an {@link InetAddress} of a remote endpoint is trusted.
     */
    public ServerBuilder clientAddressTrustedProxyFilter(
            Predicate<? super InetAddress> clientAddressTrustedProxyFilter) {
        this.clientAddressTrustedProxyFilter =
                requireNonNull(clientAddressTrustedProxyFilter, "clientAddressTrustedProxyFilter");
        return this;
    }

    /**
     * Sets a filter which evaluates whether an {@link InetAddress} can be used as a client address.
     */
    public ServerBuilder clientAddressFilter(Predicate<? super InetAddress> clientAddressFilter) {
        this.clientAddressFilter = requireNonNull(clientAddressFilter, "clientAddressFilter");
        return this;
    }

    /**
     * Sets a {@link Function} to use when determining the client address from {@link ProxiedAddresses}.
     * If not set, the {@link ProxiedAddresses#sourceAddress()}} is used as a client address.
     */
    public ServerBuilder clientAddressMapper(
            Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper) {
        this.clientAddressMapper = requireNonNull(clientAddressMapper, "clientAddressMapper");
        return this;
    }

    /**
     * Sets the default access logger name for all {@link VirtualHost}s.
     * The {@link VirtualHost}s which do not have an access logger specified by a {@link VirtualHostBuilder}
     * will have the same access {@link Logger} named the {@code loggerName}
     * when {@link ServerBuilder#build()} is called.
     */
    public ServerBuilder accessLogger(String loggerName) {
        requireNonNull(loggerName, "loggerName");
        return accessLogger(LoggerFactory.getLogger(loggerName));
    }

    /**
     * Sets the default access {@link Logger} for all {@link VirtualHost}s.
     * The {@link VirtualHost}s which do not have an access logger specified by a {@link VirtualHostBuilder}
     * will have the same access {@link Logger} when {@link ServerBuilder#build()} is called.
     */
    public ServerBuilder accessLogger(Logger logger) {
        requireNonNull(logger, "logger");
        return accessLogger(host -> logger);
    }

    /**
     * Sets the default access logger mapper for all {@link VirtualHost}s.
     * The {@link VirtualHost}s which do not have an access logger specified by a {@link VirtualHostBuilder}
     * will have an access logger set by the {@code mapper} when {@link ServerBuilder#build()} is called.
     */
    public ServerBuilder accessLogger(Function<? super VirtualHost, ? extends Logger> mapper) {
        virtualHostTemplate.accessLogger(mapper);
        return this;
    }

    /**
     * Sets the {@link RejectedRouteHandler} which will be invoked when an attempt to bind
     * an {@link HttpService} at a certain {@link Route} is rejected. By default, the duplicate
     * routes are logged at WARN level.
     */
    public ServerBuilder rejectedRouteHandler(RejectedRouteHandler handler) {
        virtualHostTemplate.rejectedRouteHandler(handler);
        return this;
    }

    /**
     * Sets the response header not to include default {@code "Server"} header.
     */
    public ServerBuilder disableServerHeader() {
        enableServerHeader = false;
        return this;
    }

    /**
     * Sets the response header not to include default {@code "Date"} header.
     */
    public ServerBuilder disableDateHeader() {
        enableDateHeader = false;
        return this;
    }

    /**
     * Adds the default HTTP header for an {@link HttpResponse} served by the default {@link VirtualHost}.
     *
     * <p>Note that the default header could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostBuilder#addHeader(CharSequence, Object)}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#addHeader(CharSequence, Object)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#addHeader(CharSequence, Object)}</li>
     * </ul>
     */
    @UnstableApi
    public ServerBuilder addHeader(CharSequence name, Object value) {
        virtualHostTemplate.addHeader(name, value);
        return this;
    }

    /**
     * Adds the default HTTP headers for an {@link HttpResponse} served by the default {@link VirtualHost}.
     *
     * <p>Note that the default headers could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostBuilder#addHeaders(Iterable)}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#addHeaders(Iterable)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#addHeaders(Iterable)}</li>
     * </ul>
     */
    @UnstableApi
    public ServerBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        virtualHostTemplate.addHeaders(defaultHeaders);
        return this;
    }

    /**
     * Adds the default HTTP header for an {@link HttpResponse} served by the default {@link VirtualHost}.
     *
     * <p>Note that the default header could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostBuilder#setHeader(CharSequence, Object)}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#setHeader(CharSequence, Object)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#setHeader(CharSequence, Object)}</li>
     * </ul>
     */
    @UnstableApi
    public ServerBuilder setHeader(CharSequence name, Object value) {
        virtualHostTemplate.setHeader(name, value);
        return this;
    }

    /**
     * Sets the default HTTP headers for an {@link HttpResponse} served by the default {@link VirtualHost}.
     *
     * <p>Note that the default headers could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostBuilder#setHeaders(Iterable)}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#setHeaders(Iterable)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#setHeaders(Iterable)}</li>
     * </ul>
     */
    @UnstableApi
    public ServerBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        virtualHostTemplate.setHeaders(defaultHeaders);
        return this;
    }

    /**
     * Sets the {@link Supplier} which generates a {@link RequestId}.
     * By default, a {@link RequestId} is generated from a random 64-bit integer.
     *
     * @deprecated Use {@link #requestIdGenerator(Function)}
     */
    @Deprecated
    public ServerBuilder requestIdGenerator(Supplier<? extends RequestId> requestIdSupplier) {
        requireNonNull(requestIdSupplier, "requestIdSupplier");
        return requestIdGenerator(routingContext -> requestIdSupplier.get());
    }

    /**
     * Sets the {@link Function} that generates a {@link RequestId} for each {@link Request}.
     * By default, a {@link RequestId} is generated from a random 64-bit integer.
     *
     * @see RequestContext#id()
     */
    public ServerBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        virtualHostTemplate.requestIdGenerator(requestIdGenerator);
        return this;
    }

    /**
     * Sets the timeout of a request.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    public ServerBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    /**
     * Sets the timeout of a request in milliseconds.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ServerBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        virtualHostTemplate.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    /**
     * Sets the maximum allowed length of the content decoded at the session layer.
     * e.g. the content length of an HTTP request.
     *
     * @param maxRequestLength the maximum allowed length. {@code 0} disables the length limit.
     */
    public ServerBuilder maxRequestLength(long maxRequestLength) {
        virtualHostTemplate.maxRequestLength(maxRequestLength);
        return this;
    }

    /**
     * Sets whether the verbose response mode is enabled. When enabled, the server responses will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the server responses will not expose such server-side details to the client.
     * The default value of this property is retrieved from {@link Flags#verboseResponses()}.
     */
    public ServerBuilder verboseResponses(boolean verboseResponses) {
        virtualHostTemplate.verboseResponses(verboseResponses);
        return this;
    }

    /**
     * Sets the {@link RequestConverterFunction}s, {@link ResponseConverterFunction}
     * and {@link ExceptionHandlerFunction}s for creating an {@link AnnotatedServiceExtensions}.
     *
     * @param requestConverterFunctions the {@link RequestConverterFunction}s
     * @param responseConverterFunctions the {@link ResponseConverterFunction}s
     * @param exceptionHandlerFunctions the {@link ExceptionHandlerFunction}s
     */
    public ServerBuilder annotatedServiceExtensions(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        virtualHostTemplate.annotatedServiceExtensions(requestConverterFunctions,
                                                       responseConverterFunctions,
                                                       exceptionHandlerFunctions);
        return this;
    }

    /**
     * Sets the {@link AutoCloseable} which will be called whenever this {@link RequestContext} is popped
     * from the {@link RequestContextStorage}.
     *
     * @param contextHook the {@link Supplier} that provides the {@link AutoCloseable}
     */
    @UnstableApi
    public ServerBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        requireNonNull(contextHook, "contextHook");
        virtualHostTemplate.contextHook(contextHook);
        return this;
    }

    /**
     * Sets the {@link DependencyInjector} to inject dependencies in annotated services.
     *
     * @param dependencyInjector the {@link DependencyInjector} to inject dependencies
     * @param shutdownOnStop whether to shut down the {@link DependencyInjector} when the {@link Server} stops
     */
    @UnstableApi
    public ServerBuilder dependencyInjector(DependencyInjector dependencyInjector, boolean shutdownOnStop) {
        requireNonNull(dependencyInjector, "dependencyInjector");
        if (this.dependencyInjector == null) {
            // Apply BuiltInDependencyInjector at first if a DependencyInjector is set.
            this.dependencyInjector = BuiltInDependencyInjector.INSTANCE;
        }
        this.dependencyInjector = this.dependencyInjector.orElse(dependencyInjector);
        if (shutdownOnStop) {
            shutdownSupports.add(ShutdownSupport.of(dependencyInjector));
        }
        return this;
    }

    /**
     * Sets the {@link Function} that transforms the absolute URI in an HTTP/1 request line
     * into an absolute path. Use this property when you have to handle a client that sends
     * such an HTTP/1 request, because Armeria always assumes that request path is an absolute path and
     * return a {@code 400 Bad Request} response otherwise. For example:
     * <pre>{@code
     * builder.absoluteUriTransformer(absoluteUri -> {
     *   // https://foo.com/bar -> /bar
     *   return absoluteUri.replaceFirst("^https://\\.foo\\.com/", "/");
     *   // or..
     *   // return "/proxy?uri=" + URLEncoder.encode(absoluteUri);
     * });
     * }</pre>
     */
    @UnstableApi
    public ServerBuilder absoluteUriTransformer(Function<? super String, String> absoluteUriTransformer) {
        this.absoluteUriTransformer = requireNonNull(absoluteUriTransformer, "absoluteUriTransformer");
        return this;
    }

    /**
     * Sets the {@link Http1HeaderNaming} which converts a lower-cased HTTP/2 header name into
     * another HTTP/1 header name. This is useful when communicating with a legacy system that only supports
     * case-sensitive HTTP/1 headers.
     */
    public ServerBuilder http1HeaderNaming(Http1HeaderNaming http1HeaderNaming) {
        requireNonNull(http1HeaderNaming, "http1HeaderNaming");
        this.http1HeaderNaming = http1HeaderNaming;
        return this;
    }

    /**
     * Sets the interval between reporting exceptions which is not logged
     * by any decorators or services such as {@link LoggingService}.
     * @param unhandledExceptionsReportInterval the interval between reports,
     *        or {@link Duration#ZERO} to disable this feature
     * @throws IllegalArgumentException if specified {@code interval} is negative.
     *
     * @deprecated Use {@link #unloggedExceptionsReportInterval(Duration)} instead.
     */
    @Deprecated
    public ServerBuilder unhandledExceptionsReportInterval(Duration unhandledExceptionsReportInterval) {
        return unloggedExceptionsReportInterval(unhandledExceptionsReportInterval);
    }

    /**
     * Sets the interval between reporting exceptions which is not logged
     * by any decorators or services such as {@link LoggingService}.
     * @param unhandledExceptionsReportIntervalMillis the interval between reports in milliseconds,
     *        or {@code 0} to disable this feature
     * @throws IllegalArgumentException if specified {@code interval} is negative.
     *
     * @deprecated Use {@link #unloggedExceptionsReportIntervalMillis(long)} instead.
     */
    @Deprecated
    public ServerBuilder unhandledExceptionsReportIntervalMillis(long unhandledExceptionsReportIntervalMillis) {
        return unloggedExceptionsReportIntervalMillis(unhandledExceptionsReportIntervalMillis);
    }

    /**
     * Sets the interval between reporting exceptions which is not logged
     * by any decorators or services such as {@link LoggingService}.
     * @param unloggedExceptionsReportInterval the interval between reports,
     *        or {@link Duration#ZERO} to disable this feature
     * @throws IllegalArgumentException if specified {@code interval} is negative.
     */
    @UnstableApi
    public ServerBuilder unloggedExceptionsReportInterval(Duration unloggedExceptionsReportInterval) {
        requireNonNull(unloggedExceptionsReportInterval, "unloggedExceptionsReportInterval");
        checkArgument(!unloggedExceptionsReportInterval.isNegative());
        return unloggedExceptionsReportIntervalMillis(unloggedExceptionsReportInterval.toMillis());
    }

    /**
     * Sets the interval between reporting exceptions which is not logged
     * by any decorators or services such as {@link LoggingService}.
     * @param unloggedExceptionsReportIntervalMillis the interval between reports in milliseconds,
     *        or {@code 0} to disable this feature
     * @throws IllegalArgumentException if specified {@code interval} is negative.
     */
    @UnstableApi
    public ServerBuilder unloggedExceptionsReportIntervalMillis(long unloggedExceptionsReportIntervalMillis) {
        checkArgument(unloggedExceptionsReportIntervalMillis >= 0,
                      "unloggedExceptionsReportIntervalMillis: %s (expected: >= 0)",
                      unloggedExceptionsReportIntervalMillis);
        this.unloggedExceptionsReportIntervalMillis = unloggedExceptionsReportIntervalMillis;
        return this;
    }

    /**
     * Returns a newly-created {@link Server} based on the configuration properties set so far.
     */
    public Server build() {
        final Server server = new Server(buildServerConfig(ports));
        serverListeners.forEach(server::addListener);
        return server;
    }

    DefaultServerConfig buildServerConfig(ServerConfig existingConfig) {
        return buildServerConfig(existingConfig.ports());
    }

    private DefaultServerConfig buildServerConfig(List<ServerPort> serverPorts) {
        final AnnotatedServiceExtensions extensions =
                virtualHostTemplate.annotatedServiceExtensions();
        assert extensions != null;
        final DependencyInjector dependencyInjector = dependencyInjectorOrReflective();

        final UnloggedExceptionsReporter unloggedExceptionsReporter;
        if (unloggedExceptionsReportIntervalMillis > 0) {
            unloggedExceptionsReporter = UnloggedExceptionsReporter.of(
                    meterRegistry, unloggedExceptionsReportIntervalMillis);
            serverListeners.add(unloggedExceptionsReporter);
        } else {
            unloggedExceptionsReporter = null;
        }

        final ServerErrorHandler errorHandler =
                new CorsServerErrorHandler(
                        this.errorHandler == null ? ServerErrorHandler.ofDefault()
                                                  : this.errorHandler.orElse(ServerErrorHandler.ofDefault()));
        final VirtualHost defaultVirtualHost =
                defaultVirtualHostBuilder.build(virtualHostTemplate, dependencyInjector,
                                                unloggedExceptionsReporter, errorHandler);
        final List<VirtualHost> virtualHosts =
                virtualHostBuilders.stream()
                                   .map(vhb -> vhb.build(virtualHostTemplate, dependencyInjector,
                                                         unloggedExceptionsReporter, errorHandler))
                                   .collect(toImmutableList());
        // Pre-populate the domain name mapping for later matching.
        final Mapping<String, SslContext> sslContexts;
        final SslContext defaultSslContext = findDefaultSslContext(defaultVirtualHost, virtualHosts);
        final Collection<ServerPort> ports;

        for (ServerPort port : this.ports) {
            checkState(port.protocols().stream().anyMatch(p -> p != PROXY),
                       "protocols: %s (expected: at least one %s or %s)",
                       port.protocols(), HTTP, HTTPS);
        }

        // The port numbers of port-based virtual hosts must exist in 'ServerPort's.
        final List<VirtualHost> portBasedVirtualHosts = virtualHosts.stream()
                                                                    .filter(v -> v.port() > 0)
                                                                    .collect(toImmutableList());
        final List<Integer> portNumbers = this.ports.stream()
                                                    .map(port -> port.localAddress().getPort())
                                                    .filter(port -> port > 0)
                                                    .collect(toImmutableList());
        for (VirtualHost virtualHost : portBasedVirtualHosts) {
            final int virtualHostPort = virtualHost.port();
            final boolean portMatched = portNumbers.stream().anyMatch(port -> port == virtualHostPort);
            checkState(portMatched, "virtual host port: %s (expected: one of %s)",
                       virtualHostPort, portNumbers);
        }

        if (defaultSslContext == null) {
            sslContexts = null;
            if (!serverPorts.isEmpty()) {
                ports = resolveDistinctPorts(serverPorts);
                for (final ServerPort p : ports) {
                    if (p.hasTls()) {
                        throw new IllegalArgumentException("TLS not configured; cannot serve HTTPS");
                    }
                }
            } else {
                ports = ImmutableList.of(new ServerPort(0, HTTP));
            }
        } else {
            if (Flags.tlsEngineType() != TlsEngineType.OPENSSL && !SystemInfo.jettyAlpnOptionalOrAvailable()) {
                throw new IllegalStateException(
                        "TLS configured but this is Java 8 and neither OpenSSL nor Jetty ALPN could be " +
                        "detected. To use TLS with Armeria, you must either use Java 9+, enable OpenSSL, " +
                        "usually by adding a build dependency on the " +
                        "io.netty:netty-tcnative-boringssl-static artifact or enable Jetty ALPN as described " +
                        "at https://www.eclipse.org/jetty/documentation/9.4.x/alpn-chapter.html");
            }

            if (!serverPorts.isEmpty()) {
                ports = resolveDistinctPorts(serverPorts);
            } else {
                ports = ImmutableList.of(new ServerPort(0, HTTPS));
            }

            final DomainMappingBuilder<SslContext>
                    mappingBuilder = new DomainMappingBuilder<>(defaultSslContext);
            for (VirtualHost h : virtualHosts) {
                final SslContext sslCtx = h.sslContext();
                if (sslCtx != null) {
                    final String originalHostnamePattern = h.originalHostnamePattern();
                    // The SslContext for the default virtual host was added when creating DomainMappingBuilder.
                    if (!"*".equals(originalHostnamePattern)) {
                        mappingBuilder.add(originalHostnamePattern, sslCtx);
                    }
                }
            }
            sslContexts = mappingBuilder.build();
        }

        if (pingIntervalMillis > 0) {
            pingIntervalMillis = Math.max(pingIntervalMillis, MIN_PING_INTERVAL_MILLIS);
            if (idleTimeoutMillis > 0 && pingIntervalMillis >= idleTimeoutMillis) {
                pingIntervalMillis = 0;
            }
        }

        if (maxConnectionAgeMillis > 0) {
            maxConnectionAgeMillis = Math.max(maxConnectionAgeMillis, MIN_MAX_CONNECTION_AGE_MILLIS);
            if (idleTimeoutMillis == 0 || idleTimeoutMillis > maxConnectionAgeMillis) {
                idleTimeoutMillis = maxConnectionAgeMillis;
            }
        }

        final Map<ChannelOption<?>, Object> newChildChannelOptions =
                ChannelUtil.applyDefaultChannelOptions(
                        childChannelOptions, idleTimeoutMillis, pingIntervalMillis);
        final BlockingTaskExecutor blockingTaskExecutor = defaultVirtualHost.blockingTaskExecutor();

        return new DefaultServerConfig(
                ports, setSslContextIfAbsent(defaultVirtualHost, defaultSslContext),
                virtualHosts, workerGroup, shutdownWorkerGroupOnStop, startStopExecutor, maxNumConnections,
                idleTimeoutMillis, keepAliveOnPing, pingIntervalMillis, maxConnectionAgeMillis,
                maxNumRequestsPerConnection,
                connectionDrainDurationMicros, http2InitialConnectionWindowSize,
                http2InitialStreamWindowSize, http2MaxStreamsPerConnection,
                http2MaxFrameSize, http2MaxHeaderListSize,
                http2MaxResetFramesPerWindow, http2MaxResetFramesWindowSeconds,
                http1MaxInitialLineLength, http1MaxHeaderSize,
                http1MaxChunkSize, gracefulShutdownQuietPeriod, gracefulShutdownTimeout,
                blockingTaskExecutor,
                meterRegistry, proxyProtocolMaxTlvSize, channelOptions, newChildChannelOptions,
                childChannelPipelineCustomizer,
                clientAddressSources, clientAddressTrustedProxyFilter, clientAddressFilter, clientAddressMapper,
                enableServerHeader, enableDateHeader, errorHandler, sslContexts,
                http1HeaderNaming, dependencyInjector, absoluteUriTransformer,
                unloggedExceptionsReportIntervalMillis, ImmutableList.copyOf(shutdownSupports));
    }

    /**
     * Returns a list of {@link ServerPort}s which consists of distinct port numbers except for the port
     * {@code 0}. If there are the same port numbers with different {@link SessionProtocol}s,
     * their {@link SessionProtocol}s will be merged into a single {@link ServerPort} instance.
     * The returned list is sorted as the same order of the specified {@code ports}.
     */
    private static List<ServerPort> resolveDistinctPorts(List<ServerPort> ports) {
        final List<ServerPort> distinctPorts = new ArrayList<>();
        for (final ServerPort port : ports) {
            boolean found = false;
            // Do not check the port number 0 because a user may want his or her server to be bound
            // on multiple arbitrary ports.
            final InetSocketAddress portAddress = port.localAddress();
            if (portAddress.getPort() > 0) {
                for (int i = 0; i < distinctPorts.size(); i++) {
                    final ServerPort distinctPort = distinctPorts.get(i);
                    final InetSocketAddress distinctPortAddress = distinctPort.localAddress();

                    // Compare the addresses taking `DomainSocketAddress` into account.
                    final boolean hasSameAddress;
                    if (portAddress instanceof DomainSocketAddress) {
                        if (distinctPortAddress instanceof DomainSocketAddress) {
                            hasSameAddress = ((DomainSocketAddress) portAddress).path().equals(
                                    ((DomainSocketAddress) distinctPortAddress).path());
                        } else {
                            hasSameAddress = false;
                        }
                    } else {
                        hasSameAddress = portAddress.equals(distinctPortAddress);
                    }

                    // Merge two `ServerPort`s into one if their addresses are equal.
                    if (hasSameAddress) {
                        final ServerPort merged =
                                new ServerPort(distinctPort.localAddress(),
                                               Sets.union(distinctPort.protocols(), port.protocols()));
                        distinctPorts.set(i, merged);
                        found = true;
                        break;
                    }
                }
            }

            if (!found) {
                distinctPorts.add(port);
            }
        }
        return Collections.unmodifiableList(distinctPorts);
    }

    private DependencyInjector dependencyInjectorOrReflective() {
        if (dependencyInjector != null) {
            return dependencyInjector;
        }
        final ReflectiveDependencyInjector reflectiveDependencyInjector = new ReflectiveDependencyInjector();
        shutdownSupports.add(ShutdownSupport.of(reflectiveDependencyInjector));
        return reflectiveDependencyInjector;
    }

    private static VirtualHost setSslContextIfAbsent(VirtualHost h,
                                                     @Nullable SslContext defaultSslContext) {
        if (h.sslContext() != null || defaultSslContext == null) {
            return h;
        }
        return h.withNewSslContext(defaultSslContext);
    }

    @Nullable
    private static SslContext findDefaultSslContext(VirtualHost defaultVirtualHost,
                                                    List<VirtualHost> virtualHosts) {
        final SslContext defaultSslContext = defaultVirtualHost.sslContext();
        if (defaultSslContext != null) {
            return defaultSslContext;
        }

        for (int i = virtualHosts.size() - 1; i >= 0; i--) {
            final SslContext sslContext = virtualHosts.get(i).sslContext();
            if (sslContext != null) {
                return sslContext;
            }
        }
        return null;
    }

    private static void warnIfServiceHasMultipleRoutes(String path, HttpService service) {
        if (service instanceof ServiceWithRoutes) {
            if (!Flags.reportMaskedRoutes()) {
                return;
            }

            if (((ServiceWithRoutes) service).routes().size() > 0) {
                logger.warn("The service has self-defined routes but the routes will be ignored. " +
                            "It will be served at the route you specified: path={}, service={}. " +
                            "If this is intended behavior, you can disable this log message by specifying " +
                            "the -Dcom.linecorp.armeria.reportMaskedRoutes=false JVM option.",
                            path, service);
            }
        }
    }
}
