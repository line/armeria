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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.server.ServerSslContextUtil.validateSslContext;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.net.ssl.SSLSession;

import org.jctools.maps.NonBlockingHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeterBinders;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;
import com.linecorp.armeria.common.util.ShutdownHooks;
import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.common.util.Version;
import com.linecorp.armeria.internal.common.RequestTargetCache;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.server.websocket.WebSocketService;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Listens to {@link ServerPort}s and delegates client requests to {@link Service}s.
 *
 * @see ServerBuilder
 */
public final class Server implements ListenableAsyncCloseable {

    static final Logger logger = LoggerFactory.getLogger(Server.class);

    /**
     * Creates a new {@link ServerBuilder}.
     */
    public static ServerBuilder builder() {
        return new ServerBuilder();
    }

    private final UpdatableServerConfig config;
    private final StartStopSupport<Void, Void, Void, ServerListener> startStop;
    private final Set<ServerChannel> serverChannels = new NonBlockingHashSet<>();
    private final ReentrantLock lock = new ReentrantShortLock();
    @GuardedBy("lock")
    private final Map<InetSocketAddress, ServerPort> activePorts = new LinkedHashMap<>();
    private final ConnectionLimitingHandler connectionLimitingHandler;
    private boolean hasWebSocketService;

    @Nullable
    @VisibleForTesting
    ServerBootstrap serverBootstrap;

    Server(DefaultServerConfig serverConfig) {
        serverConfig.setServer(this);
        config = new UpdatableServerConfig(requireNonNull(serverConfig, "serverConfig"));
        startStop = new ServerStartStopSupport(config.startStopExecutor());
        connectionLimitingHandler = new ConnectionLimitingHandler(config.maxNumConnections(),
                                                                  config.serverMetrics());

        // Server-wide metrics.
        RequestTargetCache.registerServerMetrics(config.meterRegistry());
        setupVersionMetrics();

        for (VirtualHost virtualHost : config().virtualHosts()) {
            if (virtualHost.sslContext() != null) {
                assert virtualHost.tlsEngineType() != null;
                setupTlsMetrics(virtualHost.sslContext(), virtualHost.tlsEngineType(),
                                virtualHost.hostnamePattern());
            }
        }

        // Invoke the serviceAdded() method in Service so that it can keep the reference to this Server or
        // add a listener to it.
        for (ServiceConfig cfg : config.serviceConfigs()) {
            ServiceCallbackInvoker.invokeServiceAdded(cfg, cfg.service());
        }
        hasWebSocketService = hasWebSocketService(config);
    }

    /**
     * Returns the configuration of this {@link Server}.
     */
    public ServerConfig config() {
        return config;
    }

    /**
     * Returns the information of all available {@link Service}s in the {@link Server}.
     */
    public List<ServiceConfig> serviceConfigs() {
        return config.serviceConfigs();
    }

    /**
     * Returns the hostname of the default {@link VirtualHost}, which is the hostname of the machine.
     */
    public String defaultHostname() {
        return config().defaultVirtualHost().defaultHostname();
    }

    /**
     * Returns all {@link ServerPort}s that this {@link Server} is listening to.
     *
     * @return a {@link Map} whose key is the bind address and value is {@link ServerPort}.
     *         an empty {@link Map} if this {@link Server} did not start.
     *
     * @see Server#activePort()
     */
    public Map<InetSocketAddress, ServerPort> activePorts() {
        lock.lock();
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(activePorts));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the primary {@link ServerPort} that this {@link Server} is listening to. If this {@link Server}
     * has both a local port and a non-local port, the non-local port is returned.
     *
     * @return the primary {@link ServerPort}, or {@code null} if this {@link Server} did not start.
     */
    @Nullable
    public ServerPort activePort() {
        return activePort0(null);
    }

    /**
     * Returns the primary {@link ServerPort} which serves the given {@link SessionProtocol}
     * that this {@link Server} is listening to. If this {@link Server} has both a local port and
     * a non-local port, the non-local port is returned.
     *
     * @return the primary {@link ServerPort}, or {@code null} if there is no active port available for
     *         the given {@link SessionProtocol}.
     */
    @Nullable
    public ServerPort activePort(SessionProtocol protocol) {
        return activePort0(requireNonNull(protocol, "protocol"));
    }

    @Nullable
    private ServerPort activePort0(@Nullable SessionProtocol protocol) {
        ServerPort candidate = null;
        lock.lock();
        try {
            for (ServerPort serverPort : activePorts.values()) {
                if (protocol == null || serverPort.hasProtocol(protocol)) {
                    if (!isLocalPort(serverPort)) {
                        return serverPort;
                    } else if (candidate == null) {
                        candidate = serverPort;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        return candidate;
    }

    /**
     * Returns the local {@link ServerPort} that this {@link Server} is listening to.
     *
     * @throws IllegalStateException if there is no active local port available
     *                               or the server is not started yet
     */
    public int activeLocalPort() {
        return activeLocalPort0(null);
    }

    /**
     * Returns the local {@link ServerPort} which serves the given {@link SessionProtocol}.
     *
     * @throws IllegalStateException if there is no active local port available for the given
     *                               {@link SessionProtocol} or the server is not started yet
     */
    public int activeLocalPort(SessionProtocol protocol) {
        return activeLocalPort0(requireNonNull(protocol, "protocol"));
    }

    private int activeLocalPort0(@Nullable SessionProtocol protocol) {
        lock.lock();
        try {
            return activePorts.values().stream()
                              .filter(activePort -> (protocol == null || activePort.hasProtocol(protocol)) &&
                                                    isLocalPort(activePort))
                              .findFirst()
                              .orElseThrow(() -> new IllegalStateException(
                                      (protocol == null ? "no active local ports: "
                                                        : ("no active local ports for " + protocol + ": ")) +
                                      activePorts.values()))
                              .localAddress()
                              .getPort();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     */
    public MeterRegistry meterRegistry() {
        return config().meterRegistry();
    }

    /**
     * Adds the specified {@link ServerListener} to this {@link Server}, so that it is notified when the state
     * of this {@link Server} changes. This method is useful when you want to initialize/destroy the resources
     * associated with a {@link Service}:
     * <pre>{@code
     * > public class MyService extends SimpleService {
     * >     @Override
     * >     public void serviceAdded(Server server) {
     * >         server.addListener(new ServerListenerAdapter() {
     * >             @Override
     * >             public void serverStarting() {
     * >                 ... initialize ...
     * >             }
     * >
     * >             @Override
     * >             public void serverStopped() {
     * >                 ... destroy ...
     * >             }
     * >         }
     * >     }
     * > }
     * }</pre>
     */
    public void addListener(ServerListener listener) {
        startStop.addListener(requireNonNull(listener, "listener"));
    }

    /**
     * Removes the specified {@link ServerListener} from this {@link Server}, so that it is not notified
     * anymore when the state of this {@link Server} changes.
     */
    public boolean removeListener(ServerListener listener) {
        return startStop.removeListener(requireNonNull(listener, "listener"));
    }

    /**
     * Starts this {@link Server} to listen to the {@link ServerPort}s specified in the {@link ServerConfig}.
     * Note that the startup procedure is asynchronous and thus this method returns immediately. To wait until
     * this {@link Server} is fully started up, wait for the returned {@link CompletableFuture}:
     * <pre>{@code
     * ServerBuilder builder = Server.builder();
     * ...
     * Server server = builder.build();
     * server.start().get();
     * }</pre>
     */
    public CompletableFuture<Void> start() {
        return startStop.start(true);
    }

    /**
     * Stops this {@link Server} to close all active {@link ServerPort}s. Note that the shutdown procedure is
     * asynchronous and thus this method returns immediately. To wait until this {@link Server} is fully
     * shut down, wait for the returned {@link CompletableFuture}:
     * <pre>{@code
     * Server server = ...;
     * server.stop().get();
     * }</pre>
     */
    public CompletableFuture<Void> stop() {
        return startStop.stop();
    }

    /**
     * Returns a {@link EventLoop} from the worker group. This can be used for, e.g., scheduling background
     * tasks for the lifetime of the {@link Server} using
     * {@link EventLoop#scheduleAtFixedRate(Runnable, long, long, TimeUnit)}. It is very important that these
     * tasks do not block as this would block all requests in the server on that {@link EventLoop}.
     */
    public EventLoop nextEventLoop() {
        return config().workerGroup().next();
    }

    @Override
    public boolean isClosing() {
        return startStop.isClosing();
    }

    @Override
    public boolean isClosed() {
        return startStop.isClosed();
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return startStop.whenClosed();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return startStop.closeAsync();
    }

    @Override
    public void close() {
        startStop.close();
    }

    /**
     * Returns the number of open connections on this {@link Server}.
     */
    public int numConnections() {
        return connectionLimitingHandler.numConnections();
    }

    /**
     * Waits until the result of {@link CompletableFuture} which is completed after the {@link #close()} or
     * {@link #closeAsync()} operation is completed.
     */
    public void blockUntilShutdown() throws InterruptedException {
        try {
            whenClosed().get();
        } catch (ExecutionException e) {
            throw new CompletionException(e.toString(), Exceptions.peel(e));
        }
    }

    /**
     * Sets up the version metrics.
     */
    @VisibleForTesting
    void setupVersionMetrics() {
        final MeterRegistry meterRegistry = config().meterRegistry();
        final Version versionInfo = Version.get("armeria", Server.class.getClassLoader());
        final String version = versionInfo.artifactVersion();
        final String commit = versionInfo.longCommitHash();
        final String repositoryStatus = versionInfo.repositoryStatus();
        final List<Tag> tags = ImmutableList.of(Tag.of("version", version),
                                                Tag.of("commit", commit),
                                                Tag.of("repo.status", repositoryStatus));
        Gauge.builder("armeria.build.info", () -> 1)
             .tags(tags)
             .description("A metric with a constant '1' value labeled by version and commit hash" +
                          " from which Armeria was built.")
             .register(meterRegistry);
    }

    /**
     * Sets up gauge metric for each server certificate.
     */
    private void setupTlsMetrics(SslContext sslContext, TlsEngineType tlsEngineType, String hostnamePattern) {
        final MeterRegistry meterRegistry = config().meterRegistry();

        final SSLSession sslSession = validateSslContext(sslContext, tlsEngineType);
        final MeterIdPrefix meterIdPrefix = new MeterIdPrefix("armeria.server",
                                                              "hostname.pattern", hostnamePattern);
        for (Certificate certificate : sslSession.getLocalCertificates()) {
            if (!(certificate instanceof X509Certificate)) {
                continue;
            }

            try {
                MoreMeterBinders.certificateMetrics((X509Certificate) certificate, meterIdPrefix)
                                .bindTo(meterRegistry);
            } catch (Exception ex) {
                logger.warn("Failed to set up TLS certificate metrics for a host: {}", hostnamePattern, ex);
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("config", config())
                          .add("activePorts", activePorts())
                          .add("state", startStop)
                          .toString();
    }

    /**
     * Reconfigure Server configuration. This feature is only available once a server is configured
     * and started. We do not allow ports to be reconfigured.
     */
    public void reconfigure(ServerConfigurator serverConfigurator) {
        requireNonNull(serverConfigurator, "serverConfigurator");
        final ServerBuilder sb = builder();
        serverConfigurator.reconfigure(sb);
        final DefaultServerConfig newConfig = sb.buildServerConfig(config());
        newConfig.setServer(this);
        config.updateConfig(newConfig);
        // Invoke the serviceAdded() method in Service so that it can keep the reference to this Server or
        // add a listener to it.
        config.serviceConfigs().forEach(cfg -> ServiceCallbackInvoker.invokeServiceAdded(cfg, cfg.service()));
        hasWebSocketService = hasWebSocketService(config);
    }

    private static boolean hasWebSocketService(UpdatableServerConfig config) {
        return config.serviceConfigs()
                     .stream()
                     .anyMatch(serviceConfig -> serviceConfig.service().as(WebSocketService.class) != null);
    }

    /**
     * Registers a JVM shutdown hook that closes this {@link Server} when the current JVM terminates.
     */
    public CompletableFuture<Void> closeOnJvmShutdown() {
        return ShutdownHooks.addClosingTask(this);
    }

    /**
     * Registers a JVM shutdown hook that closes this {@link Server} when the current JVM terminates.
     *
     * @param whenClosing the {@link Runnable} will be run before closing this {@link Server}
     */
    public CompletableFuture<Void> closeOnJvmShutdown(Runnable whenClosing) {
        requireNonNull(whenClosing, "whenClosing");
        return ShutdownHooks.addClosingTask(this, whenClosing);
    }

    private final class ServerStartStopSupport extends StartStopSupport<Void, Void, Void, ServerListener> {

        @Nullable
        private volatile GracefulShutdownSupport gracefulShutdownSupport;

        ServerStartStopSupport(Executor startStopExecutor) {
            super(startStopExecutor);
        }

        @Override
        protected CompletionStage<Void> doStart(@Nullable Void arg) {
            if (config().gracefulShutdownQuietPeriod().isZero()) {
                gracefulShutdownSupport = GracefulShutdownSupport.createDisabled();
            } else {
                gracefulShutdownSupport =
                        GracefulShutdownSupport.create(config().gracefulShutdownQuietPeriod(),
                                                       config().blockingTaskExecutor());
            }

            // Initialize the server sockets asynchronously.
            final CompletableFuture<Void> future = new CompletableFuture<>();
            final List<ServerPort> ports = config().ports();

            final Iterator<ServerPort> it = ports.iterator();
            assert it.hasNext();

            final ServerPort primary = it.next();
            try {
                doStart(primary).addListener(new ServerPortStartListener(primary))
                                .addListener(new NextServerPortStartListener(this, it, future));
                setupServerMetrics();
            } catch (Throwable cause) {
                future.completeExceptionally(cause);
            }

            return future;
        }

        private ChannelFuture doStart(ServerPort port) {
            final ServerBootstrap b = new ServerBootstrap();
            serverBootstrap = b;
            config.channelOptions().forEach((k, v) -> {
                @SuppressWarnings("unchecked")
                final ChannelOption<Object> castOption = (ChannelOption<Object>) k;
                b.option(castOption, v);
            });
            config.childChannelOptions().forEach((k, v) -> {
                if (!(port.isDomainSocket() && ChannelUtil.isTcpOption(k))) {
                    @SuppressWarnings("unchecked")
                    final ChannelOption<Object> castOption = (ChannelOption<Object>) k;
                    b.childOption(castOption, v);
                }
            });

            final EventLoopGroup bossGroup = EventLoopGroups.newEventLoopGroup(1, r -> {
                final FastThreadLocalThread thread = new FastThreadLocalThread(r, bossThreadName(port));
                thread.setDaemon(false);
                return thread;
            });

            b.group(bossGroup, config.workerGroup());
            b.handler(connectionLimitingHandler);
            b.childHandler(new HttpServerPipelineConfigurator(config, port, gracefulShutdownSupport,
                                                              hasWebSocketService));

            final SocketAddress localAddress;
            final Class<? extends ServerChannel> channelType;
            final TransportType transportType = Flags.transportType();
            if (port.isDomainSocket()) {
                if (transportType.supportsDomainSockets()) {
                    // Convert to Netty's DomainSocketAddress type.
                    localAddress = ((DomainSocketAddress) port.localAddress()).asNettyAddress();
                    channelType = transportType.domainServerChannelType();
                } else {
                    throw new IllegalStateException(
                            "Unix domains sockets not supported by the current transport type: " +
                            transportType.name());
                }
            } else {
                localAddress = port.localAddress();
                channelType = transportType.serverChannelType();
            }

            b.channel(channelType);
            return b.bind(localAddress);
        }

        private void setupServerMetrics() {
            final MeterRegistry meterRegistry = config.meterRegistry();
            final GracefulShutdownSupport gracefulShutdownSupport = this.gracefulShutdownSupport;
            assert gracefulShutdownSupport != null;

            meterRegistry.gauge("armeria.server.pending.responses", gracefulShutdownSupport,
                                GracefulShutdownSupport::pendingResponses);
            config.serverMetrics().bindTo(meterRegistry);
        }

        @Override
        protected CompletionStage<Void> doStop(@Nullable Void arg) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            final GracefulShutdownSupport gracefulShutdownSupport = this.gracefulShutdownSupport;
            if (gracefulShutdownSupport == null ||
                gracefulShutdownSupport.completedQuietPeriod()) {
                doStop(future, null);
                return future;
            }

            // Create a single-use thread dedicated for monitoring graceful shutdown status.
            final ScheduledExecutorService gracefulShutdownExecutor =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> new Thread(r, "armeria-shutdown-0x" + Integer.toHexString(hashCode())));

            // Check every 100 ms for the server to have completed processing requests.
            final ScheduledFuture<?> quietPeriodFuture = gracefulShutdownExecutor.scheduleAtFixedRate(() -> {
                if (gracefulShutdownSupport.completedQuietPeriod()) {
                    doStop(future, gracefulShutdownExecutor);
                }
            }, 0, 100, TimeUnit.MILLISECONDS);

            // Make sure the event loop stops after the timeout, regardless of what
            // the GracefulShutdownSupport says.
            try {
                gracefulShutdownExecutor.schedule(() -> {
                    quietPeriodFuture.cancel(false);
                    doStop(future, gracefulShutdownExecutor);
                }, config.gracefulShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Can be rejected if quiet period is complete already.
            }

            return future;
        }

        /**
         * Closes all channels and terminates all event loops.
         * <ol>
         *   <li>Closes all server channels so that we don't accept any more incoming connections.</li>
         *   <li>Closes all accepted channels.</li>
         *   <li>Shuts down the worker group if necessary.</li>
         *   <li>Shuts down the boss groups.</li>
         * </ol>
         * Note that we terminate the boss groups lastly so that the JVM does not terminate itself
         * even if other threads are daemon, because boss group threads are always non-daemon.
         */
        private void doStop(CompletableFuture<Void> future,
                            @Nullable ExecutorService gracefulShutdownExecutor) {
            // Graceful shutdown is over. Terminate the temporary executor we created at stop0(future).
            if (gracefulShutdownExecutor != null) {
                gracefulShutdownExecutor.shutdownNow();
            }

            // Close all server sockets.
            final Set<Channel> serverChannels = ImmutableSet.copyOf(Server.this.serverChannels);
            ChannelUtil.close(serverChannels).handle((unused1, unused2) -> {
                // All server ports have been closed.
                lock.lock();
                try {
                    activePorts.clear();
                } finally {
                    lock.unlock();
                }

                // Close all accepted sockets.
                ChannelUtil.close(connectionLimitingHandler.children()).handle((unused3, unused4) -> {
                    // Shut down the worker group if necessary.
                    final Future<?> workerShutdownFuture;
                    if (config.shutdownWorkerGroupOnStop()) {
                        workerShutdownFuture = config.workerGroup().shutdownGracefully();
                    } else {
                        workerShutdownFuture = ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
                    }

                    workerShutdownFuture.addListener(unused5 -> {
                        final Set<EventLoopGroup> bossGroups =
                                Server.this.serverChannels.stream()
                                                          .map(ch -> ch.eventLoop().parent())
                                                          .collect(toImmutableSet());

                        // If started to shutdown before initializing a boss group,
                        // complete the future immediately.
                        if (bossGroups.isEmpty()) {
                            finishDoStop(future);
                            return;
                        }

                        // Shut down all boss groups and wait until they are terminated.
                        final AtomicInteger remainingBossGroups = new AtomicInteger(bossGroups.size());
                        bossGroups.forEach(bossGroup -> {
                            bossGroup.shutdownGracefully();
                            bossGroup.terminationFuture().addListener(unused6 -> {
                                if (remainingBossGroups.decrementAndGet() != 0) {
                                    // There are more boss groups to terminate.
                                    return;
                                }

                                // Boss groups have been terminated completely.
                                finishDoStop(future);
                            });
                        });
                    });

                    return null;
                });

                return null;
            });
        }

        private void finishDoStop(CompletableFuture<Void> future) {
            serverChannels.clear();

            final Builder<ShutdownSupport> builder = ImmutableList.builder();
            builder.addAll(config.delegate().shutdownSupports());
            for (VirtualHost virtualHost : config.virtualHosts()) {
                builder.addAll(virtualHost.shutdownSupports());
            }
            for (ServiceConfig serviceConfig : config.serviceConfigs()) {
                builder.addAll(serviceConfig.shutdownSupports());
            }

            CompletableFuture.runAsync(() -> {
                // ShutdownSupport may be blocking so run the entire block inside the startStopExecutor
                CompletableFutures.successfulAsList(builder.build()
                                                           .stream()
                                                           .map(ShutdownSupport::shutdown)
                                                           .collect(toImmutableList()), cause -> null)
                                  .thenRunAsync(() -> future.complete(null), config.startStopExecutor());
            }, config.startStopExecutor());
        }

        @Override
        protected void notifyStarting(ServerListener listener, @Nullable Void arg) throws Exception {
            listener.serverStarting(Server.this);
        }

        @Override
        protected void notifyStarted(ServerListener listener, @Nullable Void arg,
                                     @Nullable Void result) throws Exception {
            listener.serverStarted(Server.this);
        }

        @Override
        protected void notifyStopping(ServerListener listener, @Nullable Void arg) throws Exception {
            listener.serverStopping(Server.this);
        }

        @Override
        protected void notifyStopped(ServerListener listener, @Nullable Void arg) throws Exception {
            listener.serverStopped(Server.this);
        }

        @Override
        protected void rollbackFailed(Throwable cause) {
            logStopFailure(cause);
        }

        @Override
        protected void notificationFailed(ServerListener listener, Throwable cause) {
            logger.warn("Failed to notify a server listener: {}", listener, cause);
        }

        @Override
        protected void closeFailed(Throwable cause) {
            logStopFailure(cause);
        }

        private void logStopFailure(Throwable cause) {
            logger.warn("Failed to stop a server: {}", cause.getMessage(), cause);
        }
    }

    /**
     * Collects the {@link ServerSocketChannel} and {@link ServerPort} on a successful bind operation.
     */
    private final class ServerPortStartListener implements ChannelFutureListener {

        private final ServerPort port;

        ServerPortStartListener(ServerPort port) {
            this.port = requireNonNull(port, "port");
        }

        @Override
        public void operationComplete(ChannelFuture f) {
            final ServerChannel ch = (ServerChannel) f.channel();
            assert ch.eventLoop().inEventLoop();
            serverChannels.add(ch);

            if (f.isSuccess()) {
                final SocketAddress localAddress = ch.localAddress();
                final ServerPort actualPort;
                if (localAddress instanceof InetSocketAddress) {
                    actualPort = new ServerPort((InetSocketAddress) localAddress,
                                                port.protocols(),
                                                port.portGroup());
                } else if (localAddress instanceof io.netty.channel.unix.DomainSocketAddress) {
                    // Convert Netty's DomainSocketAddress to ours.
                    final DomainSocketAddress converted = DomainSocketAddress.of(
                            (io.netty.channel.unix.DomainSocketAddress) localAddress);
                    actualPort = new ServerPort(converted,
                                                port.protocols(),
                                                port.portGroup());
                } else {
                    logger.warn("Unexpected local address type: {}", localAddress.getClass().getName());
                    return;
                }

                // Update the boss thread so its name contains the actual port.
                Thread.currentThread().setName(bossThreadName(actualPort));

                lock.lock();
                try {
                    // Update the map of active ports.
                    activePorts.put(actualPort.localAddress(), actualPort);
                } finally {
                    lock.unlock();
                }

                if (logger.isInfoEnabled()) {
                    if (isLocalPort(actualPort)) {
                        port.protocols().forEach(p -> logger.info(
                                "Serving {} at {} - {}://127.0.0.1:{}/",
                                p.name(), localAddress, p.uriText(), actualPort.localAddress().getPort()));
                    } else {
                        logger.info("Serving {} at {}", Joiner.on('+').join(port.protocols()), localAddress);
                    }
                }
            }
        }
    }

    /**
     * Initiates the next bind operation if the previous bind attempt was successful.
     */
    private final class NextServerPortStartListener implements ChannelFutureListener {

        private final ServerStartStopSupport startStopSupport;
        private final Iterator<ServerPort> it;
        private final CompletableFuture<Void> future;

        NextServerPortStartListener(ServerStartStopSupport startStopSupport,
                                    Iterator<ServerPort> it, CompletableFuture<Void> future) {
            this.startStopSupport = startStopSupport;
            this.it = it;
            this.future = future;
        }

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            if (!f.isSuccess()) {
                future.completeExceptionally(f.cause());
                return;
            }
            if (!it.hasNext()) {
                future.complete(null);
                return;
            }

            final ServerPort next = it.next();
            final ServerPort actualNext;

            // Try to use the same port number if the port belongs to a group.
            // See: https://github.com/line/armeria/issues/3725
            final long portGroup = next.portGroup();
            if (portGroup != 0) {
                int previousPort = 0;
                lock.lock();
                try {
                    for (ServerPort activePort : activePorts.values()) {
                        if (activePort.portGroup() == portGroup) {
                            previousPort = activePort.localAddress().getPort();
                            break;
                        }
                    }
                } finally {
                    lock.unlock();
                }

                if (previousPort > 0) {
                    // Use the previously bound ephemeral local port.
                    actualNext = new ServerPort(
                            new InetSocketAddress(next.localAddress().getAddress(), previousPort),
                            next.protocols(), portGroup);
                } else {
                    // `next` is the first ephemeral local port.
                    actualNext = next;
                }
            } else {
                actualNext = next;
            }

            startStopSupport.doStart(actualNext)
                            .addListener(new ServerPortStartListener(actualNext))
                            .addListener(this);
        }
    }

    private static String bossThreadName(ServerPort port) {
        // e.g. 'armeria-boss-http-*:8080'
        //      'armeria-boss-http-127.0.0.1:8443'
        //      'armeria-boss-proxy+http+https-127.0.0.1:8443'
        //      'armeria-boss-http-unix:/var/run/server.sock'
        final String protocolNames = port.protocols().stream()
                                         .map(SessionProtocol::uriText)
                                         .collect(Collectors.joining("+"));
        final InetSocketAddress localAddr = port.localAddress();
        final String localAddrText;
        if (!port.isDomainSocket()) {
            localAddrText =
                    (localAddr.getAddress().isAnyLocalAddress() ? "*" : localAddr.getHostString()) +
                    ':' + localAddr.getPort();
        } else {
            localAddrText = "unix:" + localAddr;
        }

        return "armeria-boss-" + protocolNames + '-' + localAddrText;
    }

    private static boolean isLocalPort(ServerPort serverPort) {
        final InetAddress address = serverPort.localAddress().getAddress();
        return address.isAnyLocalAddress() || address.isLoopbackAddress();
    }
}
