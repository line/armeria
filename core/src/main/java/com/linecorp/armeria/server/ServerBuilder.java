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
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static com.linecorp.armeria.server.ServerConfig.validateDefaultMaxRequestLength;
import static com.linecorp.armeria.server.ServerConfig.validateDefaultRequestTimeoutMillis;
import static com.linecorp.armeria.server.ServerConfig.validateNonNegative;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
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
import io.netty.util.DomainNameMapping;
import io.netty.util.DomainNameMappingBuilder;
import io.netty.util.concurrent.GlobalEventExecutor;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

/**
 * Builds a new {@link Server} and its {@link ServerConfig}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * // Add a port to listen
 * sb.http(8080);
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
 *     sb.http(8080) // Add a port to listen
 *       .withDefaultVirtualHost() // Add services to the default virtual host.
 *           .service(...)
 *           .serviceUnder(...)
 *       .and().withVirtualHost("*.foo.com") // Add a another virtual host.
 *           .service(...)
 *           .serviceUnder(...)
 *       .and().build(); // Build a server.
 * }</pre>
 *
 *
 * <h2 id="no_port_specified">What happens if no HTTP(S) port is specified?</h2>
 *
 * <p>When no TCP/IP port number or local address is specified, {@link ServerBuilder} will automatically bind
 * to a random TCP/IP port assigned by the OS. It will serve HTTPS if you configured TLS (or HTTP otherwise),
 * e.g.
 *
 * <pre>{@code
 * // Build an HTTP server that runs on an ephemeral TCP/IP port.
 * Server httpServer = new ServerBuilder().service(...).build();
 *
 * // Build an HTTPS server that runs on an ephemeral TCP/IP port.
 * Server httpsServer = new ServerBuilder().tls(...).service(...).build();
 * }</pre>
 *
 * @see VirtualHostBuilder
 */
public final class ServerBuilder {

    // Defaults to no graceful shutdown.
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ZERO;
    private static final String DEFAULT_SERVICE_LOGGER_PREFIX = "armeria.services";
    private static final int PROXY_PROTOCOL_DEFAULT_MAX_TLV_SIZE = 65535 - 216;

    // Prohibit deprecate option
    @SuppressWarnings("deprecation")
    private static final Set<ChannelOption<?>> PROHIBITED_SOCKET_OPTIONS = ImmutableSet.of(
            ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
            ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
            ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK,
            EpollChannelOption.EPOLL_MODE);

    private final List<ServerPort> ports = new ArrayList<>();
    private final List<ServerListener> serverListeners = new ArrayList<>();
    private final List<VirtualHost> virtualHosts = new ArrayList<>();
    private final List<ChainedVirtualHostBuilder> virtualHostBuilders = new ArrayList<>();
    private final ChainedVirtualHostBuilder defaultVirtualHostBuilder = new ChainedVirtualHostBuilder(this);
    private boolean updatedDefaultVirtualHostBuilder;
    private RejectedPathMappingHandler rejectedPathMappingHandler = RejectedPathMappingHandler.WARN;

    @Nullable
    private VirtualHost defaultVirtualHost;
    private EventLoopGroup workerGroup = CommonPools.workerGroup();
    private boolean shutdownWorkerGroupOnStop;
    private Executor startStopExecutor = GlobalEventExecutor.INSTANCE;
    private final Map<ChannelOption<?>, Object> channelOptions = new Object2ObjectArrayMap<>();
    private final Map<ChannelOption<?>, Object> childChannelOptions = new Object2ObjectArrayMap<>();
    private int maxNumConnections = Flags.maxNumConnections();
    private long idleTimeoutMillis = Flags.defaultServerIdleTimeoutMillis();
    private long defaultRequestTimeoutMillis = Flags.defaultRequestTimeoutMillis();
    private long defaultMaxRequestLength = Flags.defaultMaxRequestLength();
    private int maxHttp1InitialLineLength = Flags.defaultMaxHttp1InitialLineLength();
    private int maxHttp1HeaderSize = Flags.defaultMaxHttp1HeaderSize();
    private int maxHttp1ChunkSize = Flags.defaultMaxHttp1ChunkSize();
    private int proxyProtocolMaxTlvSize = PROXY_PROTOCOL_DEFAULT_MAX_TLV_SIZE;
    private Duration gracefulShutdownQuietPeriod = DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD;
    private Duration gracefulShutdownTimeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;
    private Executor blockingTaskExecutor = CommonPools.blockingTaskExecutor();
    private MeterRegistry meterRegistry = Metrics.globalRegistry;
    private String serviceLoggerPrefix = DEFAULT_SERVICE_LOGGER_PREFIX;
    private AccessLogWriter accessLogWriter = AccessLogWriter.disabled();
    private boolean shutdownAccessLogWriterOnStop = true;

    @Nullable
    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;

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
     * interfaces using the specified protocol.
     *
     * @deprecated Use {@link #http(int)} or {@link #https(int)}.
     * @see <a href="#no_port_specified">What happens if no HTTP(S) port is specified?</a>
     */
    @Deprecated
    public ServerBuilder port(int port, String protocol) {
        return port(port, SessionProtocol.of(requireNonNull(protocol, "protocol")));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}s. Specify multiple protocols to serve more than
     * one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = new ServerBuilder();
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
     * ServerBuilder sb = new ServerBuilder();
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
     * protocol.
     *
     * @deprecated Use {@link #http(InetSocketAddress)} or {@link #https(InetSocketAddress)}.
     */
    @Deprecated
    public ServerBuilder port(InetSocketAddress localAddress, String protocol) {
        return port(localAddress, SessionProtocol.of(requireNonNull(protocol, "protocol")));
    }

    /**
     * Adds a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}s. Specify multiple protocols to serve more than one protocol on the same port:
     *
     * <pre>{@code
     * ServerBuilder sb = new ServerBuilder();
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
     * ServerBuilder sb = new ServerBuilder();
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
        requireNonNull(port, "port");
        if (port.localAddress().getPort() != 0) {
            ports.forEach(p -> checkArgument(!p.localAddress().equals(port.localAddress()),
                                             "duplicate local address: %s", port.localAddress()));
        }
        ports.add(port);
        return this;
    }

    /**
     * Sets the {@link ChannelOption} of the server socket bound by {@link Server}.
     * Note that the previously added option will be overridden if the same option is set again.
     *
     * <pre>{@code
     * ServerBuilder sb = new ServerBuilder();
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
     * ServerBuilder sb = new ServerBuilder();
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
        this.accessLogWriter = requireNonNull(accessLogWriter, "accessLogWriter");
        shutdownAccessLogWriterOnStop = shutdownOnStop;
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
     * Sets the {@link SslContext} of the default {@link VirtualHost}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder tls(SslContext sslContext) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.tls(sslContext);
        return this;
    }

    /**
     * Configures SSL or TLS of the default {@link VirtualHost} from the specified {@code keyCertChainFile}
     * and cleartext {@code keyFile}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder tls(File keyCertChainFile, File keyFile) throws SSLException {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.tls(keyCertChainFile, keyFile);
        return this;
    }

    /**
     * Configures SSL or TLS of the default {@link VirtualHost} from the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    public ServerBuilder tls(
            File keyCertChainFile, File keyFile, @Nullable String keyPassword) throws SSLException {

        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.tls(keyCertChainFile, keyFile, keyPassword);
        return this;
    }

    /**
     * Configures SSL or TLS of the default {@link VirtualHost} with an auto-generated self-signed
     * certificate. <strong>Note:</strong> You should never use this in production but only for a testing
     * purpose.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     * @throws CertificateException if failed to generate a self-signed certificate
     */
    public ServerBuilder tlsSelfSigned() throws SSLException, CertificateException {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.tlsSelfSigned();
        return this;
    }

    /**
     * Sets the {@link SslContext} of the default {@link VirtualHost}.
     *
     * @deprecated Use {@link #tls(SslContext)}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    @Deprecated
    public ServerBuilder sslContext(SslContext sslContext) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.tls(sslContext);
        return this;
    }

    /**
     * Sets the {@link SslContext} of the default {@link VirtualHost} from the specified
     * {@link SessionProtocol}, {@code keyCertChainFile} and cleartext {@code keyFile}.
     *
     * @deprecated Use {@link #tls(File, File)}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    @Deprecated
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
     * @deprecated Use {@link #tls(File, File, String)}.
     *
     * @throws IllegalStateException if the default {@link VirtualHost} has been set via
     *                               {@link #defaultVirtualHost(VirtualHost)} already
     */
    @Deprecated
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
    ServerBuilder service(T serviceWithPathMappings, Function<? super T, R> decorator) {
        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.service(serviceWithPathMappings, decorator);
        return this;
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
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public ServerBuilder annotatedService(Object service,
                                          Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, Function.identity(),
                                requireNonNull(exceptionHandlersAndConverters,
                                               "exceptionHandlersAndConverters"));
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public ServerBuilder annotatedService(Object service,
                                          Function<Service<HttpRequest, HttpResponse>,
                                                  ? extends Service<HttpRequest, HttpResponse>> decorator,
                                          Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, decorator,
                                requireNonNull(exceptionHandlersAndConverters,
                                               "exceptionHandlersAndConverters"));
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
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                requireNonNull(exceptionHandlersAndConverters,
                                               "exceptionHandlersAndConverters"));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Function<Service<HttpRequest, HttpResponse>,
                                                  ? extends Service<HttpRequest, HttpResponse>> decorator,
                                          Object... exceptionHandlersAndConverters) {

        return annotatedService(pathPrefix, service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters an iterable object of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public ServerBuilder annotatedService(String pathPrefix, Object service,
                                          Function<Service<HttpRequest, HttpResponse>,
                                                  ? extends Service<HttpRequest, HttpResponse>> decorator,
                                          Iterable<?> exceptionHandlersAndConverters) {

        defaultVirtualHostBuilderUpdated();
        defaultVirtualHostBuilder.annotatedService(pathPrefix, service, decorator,
                                                   exceptionHandlersAndConverters);
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
     *       <li>{@link #tls(SslContext)}</li>
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
        final ChainedVirtualHostBuilder virtualHostBuilder =
                new ChainedVirtualHostBuilder(hostnamePattern, this);
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
        final ChainedVirtualHostBuilder virtualHostBuilder =
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
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
                (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return this;
    }

    /**
     * Sets the {@link RejectedPathMappingHandler} which will be invoked when an attempt to bind
     * a {@link Service} at a certain {@link PathMapping} is rejected. By default, the duplicate
     * mappings are logged at WARN level.
     */
    public ServerBuilder rejectedPathMappingHandler(RejectedPathMappingHandler handler) {
        rejectedPathMappingHandler = requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Returns a newly-created {@link Server} based on the configuration properties set so far.
     */
    public Server build() {
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

        final List<ServerPort> ports;

        // Pre-populate the domain name mapping for later matching.
        final DomainNameMapping<SslContext> sslContexts;
        final SslContext defaultSslContext = findDefaultSslContext(defaultVirtualHost, virtualHosts);

        this.ports.forEach(
                port -> checkState(port.protocols().stream().anyMatch(p -> p != PROXY),
                                   "protocols: %s (expected: at least one %s or %s)",
                                   port.protocols(), HTTP, HTTPS));

        if (defaultSslContext == null) {
            sslContexts = null;
            if (!this.ports.isEmpty()) {
                ports = ImmutableList.copyOf(this.ports);
                for (final ServerPort p : ports) {
                    if (p.hasTls()) {
                        throw new IllegalArgumentException("TLS not configured; cannot serve HTTPS");
                    }
                }
            } else {
                ports = ImmutableList.of(new ServerPort(0, HTTP));
            }
        } else {
            if (!this.ports.isEmpty()) {
                ports = ImmutableList.copyOf(this.ports);
            } else {
                ports = ImmutableList.of(new ServerPort(0, HTTPS));
            }

            final DomainNameMappingBuilder<SslContext>
                    mappingBuilder = new DomainNameMappingBuilder<>(defaultSslContext);
            for (VirtualHost h : virtualHosts) {
                final SslContext sslCtx = h.sslContext();
                if (sslCtx != null) {
                    mappingBuilder.add(h.hostnamePattern(), sslCtx);
                }
            }
            sslContexts = mappingBuilder.build();
        }

        final Server server = new Server(new ServerConfig(
                ports, normalizeDefaultVirtualHost(defaultVirtualHost, defaultSslContext), virtualHosts,
                workerGroup, shutdownWorkerGroupOnStop, startStopExecutor, maxNumConnections,
                idleTimeoutMillis, defaultRequestTimeoutMillis, defaultMaxRequestLength,
                maxHttp1InitialLineLength, maxHttp1HeaderSize, maxHttp1ChunkSize,
                gracefulShutdownQuietPeriod, gracefulShutdownTimeout, blockingTaskExecutor,
                meterRegistry, serviceLoggerPrefix, accessLogWriter, shutdownAccessLogWriterOnStop,
                proxyProtocolMaxTlvSize, channelOptions, childChannelOptions), sslContexts);

        serverListeners.forEach(server::addListener);
        return server;
    }

    private VirtualHost normalizeDefaultVirtualHost(VirtualHost h,
                                                    @Nullable SslContext defaultSslContext) {
        final SslContext sslCtx = h.sslContext() != null ? h.sslContext() : defaultSslContext;
        return new VirtualHost(
                h.defaultHostname(), "*", sslCtx,
                h.serviceConfigs().stream().map(
                        e -> new ServiceConfig(e.pathMapping(), e.service(), e.loggerName().orElse(null)))
                 .collect(Collectors.toList()), h.producibleMediaTypes(), rejectedPathMappingHandler);
    }

    @Nullable
    private static SslContext findDefaultSslContext(VirtualHost defaultVirtualHost,
                                                    List<VirtualHost> virtualHosts) {
        SslContext lastSslContext = null;
        for (VirtualHost h : virtualHosts) {
            if (h.sslContext() != null) {
                lastSslContext = h.sslContext();
            }
        }
        if (defaultVirtualHost.sslContext() != null) {
            lastSslContext = defaultVirtualHost.sslContext();
        }
        return lastSslContext;
    }

    @Override
    public String toString() {
        return ServerConfig.toString(
                getClass(), ports, defaultVirtualHost, virtualHosts, workerGroup, shutdownWorkerGroupOnStop,
                maxNumConnections, idleTimeoutMillis, defaultRequestTimeoutMillis, defaultMaxRequestLength,
                maxHttp1InitialLineLength, maxHttp1HeaderSize, maxHttp1ChunkSize,
                proxyProtocolMaxTlvSize, gracefulShutdownQuietPeriod, gracefulShutdownTimeout,
                blockingTaskExecutor, meterRegistry, serviceLoggerPrefix,
                accessLogWriter, shutdownAccessLogWriterOnStop,
                channelOptions, childChannelOptions
        );
    }
}
