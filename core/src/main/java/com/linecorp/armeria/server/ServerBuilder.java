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
import static com.linecorp.armeria.server.ServerConfig.validateNonNegative;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.Mapping;
import io.netty.util.concurrent.GlobalEventExecutor;
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
public final class ServerBuilder {

    // Defaults to no graceful shutdown.
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ZERO;
    private static final int PROXY_PROTOCOL_DEFAULT_MAX_TLV_SIZE = 65535 - 216;
    private static final String DEFAULT_ACCESS_LOGGER_PREFIX = "com.linecorp.armeria.logging.access";

    // Prohibit deprecated options
    @SuppressWarnings("deprecation")
    private static final Set<ChannelOption<?>> PROHIBITED_SOCKET_OPTIONS = ImmutableSet.of(
            ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
            ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
            ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK,
            EpollChannelOption.EPOLL_MODE);

    @VisibleForTesting
    static final long MIN_PING_INTERVAL_MILLIS = 1000L;
    private static final long MIN_MAX_CONNECTION_AGE_MILLIS = 1_000L;

    static {
        RequestContextUtil.init();
    }

    private final List<ServerPort> ports = new ArrayList<>();
    private final List<ServerListener> serverListeners = new ArrayList<>();
    @VisibleForTesting
    final VirtualHostBuilder virtualHostTemplate = new VirtualHostBuilder(this, false);
    private final VirtualHostBuilder defaultVirtualHostBuilder = new VirtualHostBuilder(this, true);
    private final List<VirtualHostBuilder> virtualHostBuilders = new ArrayList<>();

    private EventLoopGroup workerGroup = CommonPools.workerGroup();
    private boolean shutdownWorkerGroupOnStop;
    private Executor startStopExecutor = GlobalEventExecutor.INSTANCE;
    private final Map<ChannelOption<?>, Object> channelOptions = new Object2ObjectArrayMap<>();
    private final Map<ChannelOption<?>, Object> childChannelOptions = new Object2ObjectArrayMap<>();
    private int maxNumConnections = Flags.maxNumConnections();
    private long idleTimeoutMillis = Flags.defaultServerIdleTimeoutMillis();
    private long pingIntervalMillis = Flags.defaultPingIntervalMillis();
    private long maxConnectionAgeMillis = Flags.defaultMaxServerConnectionAgeMillis();
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
    private ScheduledExecutorService blockingTaskExecutor = CommonPools.blockingTaskExecutor();
    private boolean shutdownBlockingTaskExecutorOnStop;
    private MeterRegistry meterRegistry = Metrics.globalRegistry;
    private List<ClientAddressSource> clientAddressSources = ClientAddressSource.DEFAULT_SOURCES;
    private Predicate<? super InetAddress> clientAddressTrustedProxyFilter = address -> false;
    private Predicate<? super InetAddress> clientAddressFilter = address -> true;
    private Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper =
            ProxiedAddresses::sourceAddress;
    private boolean enableServerHeader = true;
    private boolean enableDateHeader = true;
    private Supplier<? extends RequestId> requestIdGenerator = RequestId::random;

    ServerBuilder() {
        // Set the default host-level properties.
        virtualHostTemplate.accessLogWriter(AccessLogWriter.disabled(), true);
        virtualHostTemplate.rejectedRouteHandler(RejectedRouteHandler.WARN);
        virtualHostTemplate.requestTimeoutMillis(Flags.defaultRequestTimeoutMillis());
        virtualHostTemplate.maxRequestLength(Flags.defaultMaxRequestLength());
        virtualHostTemplate.verboseResponses(Flags.verboseResponses());
        virtualHostTemplate.accessLogger(
                host -> LoggerFactory.getLogger(defaultAccessLoggerName(host.hostnamePattern())));
        virtualHostTemplate.tlsSelfSigned(false);
        virtualHostTemplate.annotatedServiceExtensions(ImmutableList.of(), ImmutableList.of(),
                                                       ImmutableList.of());
    }

    private static String defaultAccessLoggerName(String hostnamePattern) {
        requireNonNull(hostnamePattern, "hostnamePattern");
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
        checkArgument(!PROHIBITED_SOCKET_OPTIONS.contains(option),
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
        checkArgument(!PROHIBITED_SOCKET_OPTIONS.contains(option),
                      "prohibited socket option: %s", option);

        option.validate(value);
        childChannelOptions.put(option, value);
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
     * Sets the {@link Executor} which will invoke the callbacks of {@link Server#start()},
     * {@link Server#stop()} and {@link ServerListener}. If not set, {@link GlobalEventExecutor} will be used
     * by default.
     */
    public ServerBuilder startStopExecutor(Executor startStopExecutor) {
        this.startStopExecutor = requireNonNull(startStopExecutor, "startStopExecutor");
        return this;
    }

    /**
     * Sets the maximum allowed number of open connections.
     */
    public ServerBuilder maxNumConnections(int maxNumConnections) {
        this.maxNumConnections = ServerConfig.validateMaxNumConnections(maxNumConnections);
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
     * Sets the HTTP/2 <a href="https://httpwg.org/specs/rfc7540.html#PING">PING</a> interval.
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
     * Sets the HTTP/2 <a href="https://httpwg.org/specs/rfc7540.html#PING">PING</a> interval.
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
     *
     * @param maxConnectionAge the maximum connection age. {@code 0} disables the limit.
     * @throws IllegalArgumentException if the specified {@code maxConnectionAge} is smaller than
     *                                  {@value #MIN_MAX_CONNECTION_AGE_MILLIS} milliseconds.
     */
    public ServerBuilder maxConnectionAge(Duration maxConnectionAge) {
        return maxConnectionAgeMillis(requireNonNull(maxConnectionAge, "maxConnectionAge").toMillis());
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
     * Sets the maximum size of HTTP/2 frame that can be received. Defaults to
     * {@link Flags#defaultHttp2MaxFrameSize()}.
     */
    public ServerBuilder http2MaxFrameSize(int http2MaxFrameSize) {
        checkArgument(http2MaxFrameSize >= MAX_FRAME_SIZE_LOWER_BOUND &&
                      http2MaxFrameSize <= MAX_FRAME_SIZE_UPPER_BOUND,
                      "http2MaxFramSize: %s (expected: >= %s and <= %s)",
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
        ServerConfig.validateGreaterThanOrEqual(gracefulShutdownTimeout, "quietPeriod",
                                                gracefulShutdownQuietPeriod, "timeout");
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
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        shutdownBlockingTaskExecutorOnStop = shutdownOnStop;
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
     * Sets the format of this {@link Server}'s access log. The specified {@code accessLogFormat} would be
     * parsed by {@link AccessLogWriter#custom(String)}.
     */
    public ServerBuilder accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               true);
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

    /**
     * Configures SSL or TLS of the {@link Server} from the specified {@code keyCertChainFile}
     * and cleartext {@code keyFile}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(File keyCertChainFile, File keyFile) {
        virtualHostTemplate.tls(keyCertChainFile, keyFile);
        return this;
    }

    /**
     * Configures SSL or TLS of the {@link Server} from the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(
            File keyCertChainFile, File keyFile, @Nullable String keyPassword) {
        virtualHostTemplate.tls(keyCertChainFile, keyFile, keyPassword);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link Server} with the specified {@code keyCertChainInputStream} and
     * cleartext {@code keyInputStream}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        virtualHostTemplate.tls(keyCertChainInputStream, keyInputStream);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link Server} with the specified {@code keyCertChainInputStream},
     * {@code keyInputStream} and {@code keyPassword}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream,
                             @Nullable String keyPassword) {
        virtualHostTemplate.tls(keyCertChainInputStream, keyInputStream, keyPassword);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link Server} with the specified cleartext {@link PrivateKey} and
     * {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(PrivateKey key, X509Certificate... keyCertChain) {
        virtualHostTemplate.tls(key, keyCertChain);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link Server} with the specified cleartext {@link PrivateKey} and
     * {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
        virtualHostTemplate.tls(key, keyCertChain);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link Server} with the specified {@link PrivateKey}, {@code keyPassword}
     * and {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(PrivateKey key, @Nullable String keyPassword, X509Certificate... keyCertChain) {
        virtualHostTemplate.tls(key, keyPassword, keyCertChain);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link Server} with the specified {@link PrivateKey}, {@code keyPassword}
     * and {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ServerBuilder tls(PrivateKey key, @Nullable String keyPassword,
                             Iterable<? extends X509Certificate> keyCertChain) {
        virtualHostTemplate.tls(key, keyPassword, keyCertChain);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link Server} with the specified {@link KeyManagerFactory}.
     *
     * @see #tlsCustomizer(Consumer)
     */
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

    /**
     * Adds the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public ServerBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        virtualHostTemplate.tlsCustomizer(tlsCustomizer);
        return this;
    }

    /**
     * Configures an {@link HttpService} of the default {@link VirtualHost} with the {@code customizer}.
     */
    public ServerBuilder withRoute(Consumer<? super ServiceBindingBuilder> customizer) {
        final ServiceBindingBuilder serviceBindingBuilder = new ServiceBindingBuilder(this);
        customizer.accept(serviceBindingBuilder);
        return this;
    }

    /**
     * Returns a {@link ServiceBindingBuilder} which is for binding an {@link HttpService} fluently.
     */
    public ServiceBindingBuilder route() {
        return new ServiceBindingBuilder(this);
    }

    /**
     * Returns a {@link DecoratingServiceBindingBuilder} which is for binding a {@code decorator} fluently.
     */
    public DecoratingServiceBindingBuilder routeDecorator() {
        return new DecoratingServiceBindingBuilder(this);
    }

    /**
     * Binds the specified {@link HttpService} under the specified directory of the default {@link VirtualHost}.
     */
    public ServerBuilder serviceUnder(String pathPrefix, HttpService service) {
        return service(Route.builder().pathPrefix(pathPrefix).build(), service);
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
    public ServerBuilder service(String pathPattern, HttpService service) {
        return route().path(pathPattern).build(service);
    }

    /**
     * Binds the specified {@link HttpService} at the specified {@link Route} of the default
     * {@link VirtualHost}.
     */
    public ServerBuilder service(Route route, HttpService service) {
        return route().addRoute(route).build(service);
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    public ServerBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(serviceWithRoutes, "serviceWithRoutes");
        requireNonNull(serviceWithRoutes.routes(), "serviceWithRoutes.routes()");
        requireNonNull(decorators, "decorators");

        HttpService decorated = serviceWithRoutes;
        for (Function<? super HttpService, ? extends HttpService> d : decorators) {
            checkNotNull(d, "decorators contains null: %s", decorators);
            decorated = d.apply(decorated);
            checkNotNull(decorated, "A decorator returned null: %s", d);
        }

        final HttpService decorator = decorated;
        serviceWithRoutes.routes().forEach(route -> service(route, decorator));
        return this;
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @SafeVarargs
    public final ServerBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return service(serviceWithRoutes, ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public ServerBuilder annotatedService(Object service) {
        return annotatedService("/", service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public ServerBuilder annotatedService(Object service,
                                          Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public ServerBuilder annotatedService(Object service,
                                          Function<? super HttpService, ? extends HttpService> decorator,
                                          Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service) {
        return annotatedService(pathPrefix, service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Function<? super HttpService, ? extends HttpService> decorator,
                                          Object... exceptionHandlersAndConverters) {

        return annotatedService(pathPrefix, service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Iterable<?> exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(), exceptionHandlersAndConverters);
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction},
     *                                       the {@link RequestConverterFunction} and/or
     *                                       the {@link ResponseConverterFunction}
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Function<? super HttpService, ? extends HttpService> decorator,
                                          Iterable<?> exceptionHandlersAndConverters) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlersAndConverters, "exceptionHandlersAndConverters");
        final AnnotatedServiceExtensions configurator =
                AnnotatedServiceExtensions
                        .ofExceptionHandlersAndConverters(exceptionHandlersAndConverters);
        return annotatedService(pathPrefix, service, decorator, configurator.exceptionHandlers(),
                                configurator.requestConverters(), configurator.responseConverters());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlerFunctions the {@link ExceptionHandlerFunction}s
     * @param requestConverterFunctions the {@link RequestConverterFunction}s
     * @param responseConverterFunctions the {@link ResponseConverterFunction}s
     */
    public ServerBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        return annotatedService().pathPrefix(pathPrefix)
                                 .decorator(decorator)
                                 .exceptionHandlers(exceptionHandlerFunctions)
                                 .requestConverters(requestConverterFunctions)
                                 .responseConverters(responseConverterFunctions)
                                 .build(service);
    }

    /**
     * Returns an {@link AnnotatedServiceBindingBuilder} to build annotated service.
     */
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
     */
    public ServerBuilder withVirtualHost(Consumer<? super VirtualHostBuilder> customizer) {
        final VirtualHostBuilder virtualHostBuilder = new VirtualHostBuilder(this, false);
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
        final VirtualHostBuilder virtualHostBuilder =
                new VirtualHostBuilder(this, false).hostnamePattern(hostnamePattern);
        virtualHostBuilders.add(virtualHostBuilder);
        return virtualHostBuilder;
    }

    /**
     * Adds the <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Name-based">name-based virtual host</a>.
     *
     * @param defaultHostname default hostname of this virtual host
     * @param hostnamePattern virtual host name regular expression
     * @return {@link VirtualHostBuilder} for building the virtual host
     */
    public VirtualHostBuilder virtualHost(String defaultHostname, String hostnamePattern) {
        final VirtualHostBuilder virtualHostBuilder = new VirtualHostBuilder(this, false)
                .defaultHostname(defaultHostname)
                .hostnamePattern(hostnamePattern);
        virtualHostBuilders.add(virtualHostBuilder);
        return virtualHostBuilder;
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}s
     */
    public ServerBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.ofCatchAll(), decorator);
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@link DecoratingHttpServiceFunction}.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    public ServerBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.ofCatchAll(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     */
    public ServerBuilder decorator(
            String pathPattern, Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().path(pathPattern).build(), decorator);
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}.
     */
    public ServerBuilder decorator(
            String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().path(pathPattern).build(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s with the specified {@link Route}.
     *
     * @param route the route being decorated
     * @param decorator the {@link Function} that decorates {@link HttpService} which matches
     *                  the specified {@link Route}
     */
    public ServerBuilder decorator(
            Route route, Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(route, "route");
        requireNonNull(decorator, "decorator");
        return routingDecorator(new RouteDecoratingService(route, decorator));
    }

    /**
     * Decorates {@link HttpService}s with the specified {@link Route}.
     *
     * @param route the route being decorated
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    public ServerBuilder decorator(
            Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return decorator(route, delegate -> new FunctionalDecoratingHttpService(
                delegate, decoratingHttpServiceFunction));
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    public ServerBuilder decoratorUnder(
            String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     */
    public ServerBuilder decoratorUnder(String prefix,
                                        Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decorator);
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
     * Sets the {@link Supplier} which generates a {@link RequestId}.
     * By default, a {@link RequestId} is generated from a random 64-bit integer.
     *
     * @see RequestContext#id()
     */
    public ServerBuilder requestIdGenerator(Supplier<? extends RequestId> requestIdGenerator) {
        this.requestIdGenerator = requireNonNull(requestIdGenerator, "requestIdGenerator");
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
     * Returns a newly-created {@link Server} based on the configuration properties set so far.
     */
    public Server build() {
        final AnnotatedServiceExtensions extensions =
                virtualHostTemplate.annotatedServiceExtensions();

        assert extensions != null;

        final VirtualHost defaultVirtualHost =
                defaultVirtualHostBuilder.build(virtualHostTemplate);
        final List<VirtualHost> virtualHosts =
                virtualHostBuilders.stream()
                                   .map(vhb -> vhb.build(virtualHostTemplate))
                                   .collect(toImmutableList());

        // Pre-populate the domain name mapping for later matching.
        final Mapping<String, SslContext> sslContexts;
        final SslContext defaultSslContext = findDefaultSslContext(defaultVirtualHost, virtualHosts);

        final Collection<ServerPort> ports;

        this.ports.forEach(
                port -> checkState(port.protocols().stream().anyMatch(p -> p != PROXY),
                                   "protocols: %s (expected: at least one %s or %s)",
                                   port.protocols(), HTTP, HTTPS));

        if (defaultSslContext == null) {
            sslContexts = null;
            if (!this.ports.isEmpty()) {
                ports = resolveDistinctPorts(this.ports);
                for (final ServerPort p : ports) {
                    if (p.hasTls()) {
                        throw new IllegalArgumentException("TLS not configured; cannot serve HTTPS");
                    }
                }
            } else {
                ports = ImmutableList.of(new ServerPort(0, HTTP));
            }
        } else {
            if (!Flags.useOpenSsl() && !SystemInfo.jettyAlpnOptionalOrAvailable()) {
                throw new IllegalStateException(
                        "TLS configured but this is Java 8 and neither OpenSSL nor Jetty ALPN could be " +
                        "detected. To use TLS with Armeria, you must either use Java 9+, enable OpenSSL, " +
                        "usually by adding a build dependency on the " +
                        "io.netty:netty-tcnative-boringssl-static artifact or enable Jetty ALPN as described " +
                        "at https://www.eclipse.org/jetty/documentation/9.4.x/alpn-chapter.html");
            }

            if (!this.ports.isEmpty()) {
                ports = resolveDistinctPorts(this.ports);
            } else {
                ports = ImmutableList.of(new ServerPort(0, HTTPS));
            }

            final DomainMappingBuilder<SslContext>
                    mappingBuilder = new DomainMappingBuilder<>(defaultSslContext);
            for (VirtualHost h : virtualHosts) {
                final SslContext sslCtx = h.sslContext();
                if (sslCtx != null) {
                    mappingBuilder.add(h.hostnamePattern(), sslCtx);
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

        final Server server = new Server(new ServerConfig(
                ports, setSslContextIfAbsent(defaultVirtualHost, defaultSslContext), virtualHosts,
                workerGroup, shutdownWorkerGroupOnStop, startStopExecutor, maxNumConnections,
                idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis,
                http2InitialConnectionWindowSize,
                http2InitialStreamWindowSize, http2MaxStreamsPerConnection,
                http2MaxFrameSize, http2MaxHeaderListSize, http1MaxInitialLineLength, http1MaxHeaderSize,
                http1MaxChunkSize, gracefulShutdownQuietPeriod, gracefulShutdownTimeout,
                blockingTaskExecutor, shutdownBlockingTaskExecutorOnStop,
                meterRegistry, proxyProtocolMaxTlvSize, channelOptions, childChannelOptions,
                clientAddressSources, clientAddressTrustedProxyFilter, clientAddressFilter, clientAddressMapper,
                enableServerHeader, enableDateHeader, requestIdGenerator), sslContexts);

        serverListeners.forEach(server::addListener);
        return server;
    }

    /**
     * Returns a list of {@link ServerPort}s which consists of distinct port numbers except for the port
     * {@code 0}. If there are the same port numbers with different {@link SessionProtocol}s,
     * their {@link SessionProtocol}s will be merged into a single {@link ServerPort} instance.
     * The returned list is sorted as the same order of the specified {@code ports}.
     */
    private static List<ServerPort> resolveDistinctPorts(List<ServerPort> ports) {
        final List<ServerPort> distinctPorts = new ArrayList<>();
        for (final ServerPort p : ports) {
            boolean found = false;
            // Do not check the port number 0 because a user may want his or her server to be bound
            // on multiple arbitrary ports.
            if (p.localAddress().getPort() > 0) {
                for (int i = 0; i < distinctPorts.size(); i++) {
                    final ServerPort port = distinctPorts.get(i);
                    if (port.localAddress().equals(p.localAddress())) {
                        final ServerPort merged =
                                new ServerPort(port.localAddress(),
                                               Sets.union(port.protocols(), p.protocols()));
                        distinctPorts.set(i, merged);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                distinctPorts.add(p);
            }
        }
        return Collections.unmodifiableList(distinctPorts);
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

    @Override
    public String toString() {
        return ServerConfig.toString(
                getClass(), ports, null, ImmutableList.of(), workerGroup, shutdownWorkerGroupOnStop,
                maxNumConnections, idleTimeoutMillis, http2InitialConnectionWindowSize,
                http2InitialStreamWindowSize, http2MaxStreamsPerConnection, http2MaxFrameSize,
                http2MaxHeaderListSize, http1MaxInitialLineLength, http1MaxHeaderSize, http1MaxChunkSize,
                proxyProtocolMaxTlvSize, gracefulShutdownQuietPeriod, gracefulShutdownTimeout,
                blockingTaskExecutor, shutdownBlockingTaskExecutorOnStop,
                meterRegistry, channelOptions, childChannelOptions,
                clientAddressSources, clientAddressTrustedProxyFilter, clientAddressFilter, clientAddressMapper,
                enableServerHeader, enableDateHeader);
    }
}
