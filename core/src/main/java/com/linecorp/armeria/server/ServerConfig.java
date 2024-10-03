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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;

/**
 * {@link Server} configuration.
 */
public interface ServerConfig {
    /**
     * Returns the {@link Server}.
     */
    Server server();

    /**
     * Returns the {@link ServerPort}s to listen on.
     *
     * @see Server#activePorts()
     */
    List<ServerPort> ports();

    /**
     * Returns the default {@link VirtualHost}, which is used when no other {@link VirtualHost}s match the
     * host name of a client request. e.g. the {@code "Host"} header in HTTP or host name in TLS SNI extension
     *
     * @see #virtualHosts()
     */
    VirtualHost defaultVirtualHost();

    /**
     * Returns the {@link List} of available {@link VirtualHost}s.
     *
     * @return the {@link List} of available {@link VirtualHost}s where its last {@link VirtualHost} is
     *         {@link #defaultVirtualHost()}
     */
    List<VirtualHost> virtualHosts();

    /**
     * Finds the {@link VirtualHost} that matches the specified {@code hostname}. If there's no match, the
     * {@link #defaultVirtualHost()} is returned.
     *
     * @deprecated Use {@link #findVirtualHost(String, int)} instead.
     */
    @Deprecated
    VirtualHost findVirtualHost(String hostname);

    /**
     * Finds the {@link VirtualHost} that matches the specified {@code hostname} and {@code port}.
     * The {@code port} is used to find
     * a <a href="https://en.wikipedia.org/wiki/Virtual_hosting#Port-based">port-based</a>
     * virtual host that was bound to the {@code port}.
     * If there's no match, the {@link #defaultVirtualHost()} is returned.
     *
     * @see ServerBuilder#virtualHost(int)
     */
    VirtualHost findVirtualHost(String hostname, int port);

    /**
     * Finds the {@link List} of {@link VirtualHost}s that contains the specified {@link HttpService}.
     * If there's no match, an empty {@link List} is returned. Note that this is potentially an expensive
     * operation and thus should not be used in a performance-sensitive path.
     */
    List<VirtualHost> findVirtualHosts(HttpService service);

    /**
     * Returns the information of all available {@link HttpService}s in the {@link Server}.
     */
    List<ServiceConfig> serviceConfigs();

    /**
     * Returns the worker {@link EventLoopGroup} which is responsible for performing socket I/O.
     */
    EventLoopGroup workerGroup();

    /**
     * Returns whether the worker {@link EventLoopGroup} is shut down when the {@link Server} stops.
     *
     * @deprecated This method will be hidden from public API. The {@link EventLoopGroup} is shut down if
     *             the {@code shutdownOnStop} of
     *             {@link ServerBuilder#workerGroup(EventLoopGroup, boolean)} is set to {@code true}.
     */
    @Deprecated
    boolean shutdownWorkerGroupOnStop();

    /**
     * Returns the {@link ChannelOption}s and their values of {@link Server}'s server sockets.
     */
    Map<ChannelOption<?>, ?> channelOptions();

    /**
     * Returns the {@link ChannelOption}s and their values of sockets accepted by {@link Server}.
     */
    Map<ChannelOption<?>, ?> childChannelOptions();

    /**
     * Returns the {@link Consumer} that customizes the Netty child {@link ChannelPipeline}.
     */
    @UnstableApi
    Consumer<? super ChannelPipeline> childChannelPipelineCustomizer();

    /**
     * Returns the maximum allowed number of open connections.
     */
    int maxNumConnections();

    /**
     * Returns the idle timeout of a connection in milliseconds for keep-alive.
     */
    long idleTimeoutMillis();

    /**
     * Returns whether to prevent the server from staying in an idle state when an HTTP/2 PING frame
     * is received.
     */
    @UnstableApi
    boolean keepAliveOnPing();

    /**
     * Returns the HTTP/2 PING interval in milliseconds.
     */
    long pingIntervalMillis();

    /**
     * Returns the maximum allowed age of a connection in milliseconds for keep-alive.
     */
    long maxConnectionAgeMillis();

    /**
     * Returns the graceful connection shutdown drain duration.
     */
    long connectionDrainDurationMicros();

    /**
     * Returns the maximum allowed number of requests that can be served through one connection.
     */
    int maxNumRequestsPerConnection();

    /**
     * Returns the maximum length of an HTTP/1 response initial line.
     */
    int http1MaxInitialLineLength();

    /**
     * Returns the maximum length of all headers in an HTTP/1 response.
     */
    int http1MaxHeaderSize();

    /**
     * Returns the maximum length of each chunk in an HTTP/1 response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     */
    int http1MaxChunkSize();

    /**
     * Returns the initial connection-level HTTP/2 flow control window size.
     */
    int http2InitialConnectionWindowSize();

    /**
     * Returns the initial stream-level HTTP/2 flow control window size.
     */
    int http2InitialStreamWindowSize();

    /**
     * Returns the maximum number of concurrent streams per HTTP/2 connection.
     */
    long http2MaxStreamsPerConnection();

    /**
     * Returns the maximum size of HTTP/2 frames that can be received.
     */
    int http2MaxFrameSize();

    /**
     * Returns the maximum size of headers that can be received.
     */
    long http2MaxHeaderListSize();

    /**
     * Returns the maximum number of RST frames that are allowed per
     * {@link #http2MaxResetFramesWindowSeconds()}.
     */
    @UnstableApi
    int http2MaxResetFramesPerWindow();

    /**
     * Returns the number of seconds during which {@link #http2MaxResetFramesPerWindow()} RST frames are
     * allowed.
     */
    @UnstableApi
    int http2MaxResetFramesWindowSeconds();

    /**
     * Returns the number of milliseconds to wait for active requests to go end before shutting down.
     * {@code 0} means the server will stop right away without waiting.
     */
    Duration gracefulShutdownQuietPeriod();

    /**
     * Returns the number of milliseconds to wait before shutting down the server regardless of active
     * requests.
     */
    Duration gracefulShutdownTimeout();

    /**
     * Returns the {@link BlockingTaskExecutor} dedicated to the execution of blocking tasks or invocations.
     * Note that the {@link BlockingTaskExecutor} returned by this method does not set the
     * {@link ServiceRequestContext} when executing a submitted task.
     * Use {@link ServiceRequestContext#blockingTaskExecutor()} if possible.
     */
    BlockingTaskExecutor blockingTaskExecutor();

    /**
     * Returns whether the worker {@link Executor} is shut down when the {@link Server} stops.
     *
     * @deprecated This method is not used anymore. The {@code blockingTaskExecutor} is shut down if
     *             the {@code shutdownOnStop} of
     *             {@link ServerBuilder#blockingTaskExecutor(BlockingTaskExecutor, boolean)}
     *             is set to {@code true}.
     */
    @Deprecated
    default boolean shutdownBlockingTaskExecutorOnStop() {
        return false;
    }

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     */
    MeterRegistry meterRegistry();

    /**
     * Returns the maximum size of additional data (TLV, Tag-Length-Value). It is only used when
     * PROXY protocol is enabled on the server port.
     */
    int proxyProtocolMaxTlvSize();

    /**
     * Returns a list of {@link ClientAddressSource}s which are used to determine where to look for
     * the client address, in the order of preference.
     */
    List<ClientAddressSource> clientAddressSources();

    /**
     * Returns a filter which evaluates whether an {@link InetAddress} of a remote endpoint is trusted.
     */
    Predicate<? super InetAddress> clientAddressTrustedProxyFilter();

    /**
     * Returns a filter which evaluates whether an {@link InetAddress} can be used as a client address.
     */
    Predicate<? super InetAddress> clientAddressFilter();

    /**
     * Returns a {@link Function} to use when determining the client address from {@link ProxiedAddresses}.
     */
    Function<? super ProxiedAddresses, ? extends InetSocketAddress> clientAddressMapper();

    /**
     * Returns whether the response header will include default {@code "Date"} header.
     */
    boolean isDateHeaderEnabled();

    /**
     * Returns whether the response header will include default {@code "Server"} header.
     */
    boolean isServerHeaderEnabled();

    /**
     * Returns the {@link Function} that generates a {@link RequestId} for each {@link Request}.
     *
     * @deprecated Use {@link ServiceConfig#requestIdGenerator()} or {@link VirtualHost#requestIdGenerator()}.
     */
    @UnstableApi
    @Deprecated
    Function<RoutingContext, RequestId> requestIdGenerator();

    /**
     * Returns the {@link ServerErrorHandler} that provides the error responses in case of unexpected
     * exceptions or protocol errors.
     */
    ServerErrorHandler errorHandler();

    /**
     * Returns the {@link Http1HeaderNaming} which converts a lower-cased HTTP/2 header name into
     * another HTTP/1 header name. This is useful when communicating with a legacy system that only
     * supports case-sensitive HTTP/1 headers.
     */
    Http1HeaderNaming http1HeaderNaming();

    /**
     * Returns the {@link DependencyInjector} that injects dependencies in annotations.
     */
    DependencyInjector dependencyInjector();

    /**
     * Returns the {@link Function} that transforms the absolute URI in an HTTP/1 request line
     * into an absolute path.
     */
    @UnstableApi
    Function<String, String> absoluteUriTransformer();

    /**
     * Returns the interval between reporting unlogged exceptions in milliseconds.
     *
     * @deprecated Use {@link #unloggedExceptionsReportIntervalMillis()} instead.
     */
    @Deprecated
    long unhandledExceptionsReportIntervalMillis();

    /**
     * Returns the interval between reporting unlogged exceptions in milliseconds.
     */
    long unloggedExceptionsReportIntervalMillis();

    /**
     * Returns the {@link ServerMetrics} that collects metrics related server.
     */
    ServerMetrics serverMetrics();
}
