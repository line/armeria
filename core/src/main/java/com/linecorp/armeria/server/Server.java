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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.armeria.internal.ChannelUtil;
import com.linecorp.armeria.internal.ConnectionLimitingHandler;
import com.linecorp.armeria.internal.PathAndQuery;
import com.linecorp.armeria.internal.TransportType;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Listens to {@link ServerPort}s and delegates client requests to {@link Service}s.
 *
 * @see ServerBuilder
 */
public final class Server implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private final ServerConfig config;
    @Nullable
    private final DomainNameMapping<SslContext> sslContexts;

    private final StartStopSupport<Void, ServerListener> startStop;
    private final Set<Channel> serverChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<InetSocketAddress, ServerPort> activePorts = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, ServerPort> unmodifiableActivePorts =
            Collections.unmodifiableMap(activePorts);

    private final ConnectionLimitingHandler connectionLimitingHandler;

    @Nullable
    private volatile ServerPort primaryActivePort;

    @Nullable
    private ServerBootstrap serverBootstrap;

    Server(ServerConfig config, @Nullable DomainNameMapping<SslContext> sslContexts) {
        this.config = requireNonNull(config, "config");
        this.sslContexts = sslContexts;
        startStop = new ServerStartStopSupport(config.startStopExecutor());
        connectionLimitingHandler = new ConnectionLimitingHandler(config.maxNumConnections());

        config.setServer(this);

        // Server-wide cache metrics.
        final MeterIdPrefix idPrefix = new MeterIdPrefix("armeria.server.parsedPathCache");
        PathAndQuery.registerMetrics(config.meterRegistry(), idPrefix);

        // Invoke the serviceAdded() method in Service so that it can keep the reference to this Server or
        // add a listener to it.
        config.serviceConfigs().forEach(cfg -> ServiceCallbackInvoker.invokeServiceAdded(cfg, cfg.service()));
    }

    /**
     * Returns the configuration of this {@link Server}.
     */
    public ServerConfig config() {
        return config;
    }

    /**
     * Returns the hostname of the default {@link VirtualHost}, which is the hostname of the machine unless
     * configured explicitly via {@link ServerBuilder#defaultVirtualHost(VirtualHost)}.
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
        return unmodifiableActivePorts;
    }

    /**
     * Returns the primary {@link ServerPort} that this {@link Server} is listening to. This method is useful
     * when a {@link Server} listens to only one {@link ServerPort}.
     *
     * @return {@link Optional#empty()} if this {@link Server} did not start
     */
    public Optional<ServerPort> activePort() {
        return Optional.ofNullable(primaryActivePort);
    }

    @Nullable
    @VisibleForTesting
    ServerBootstrap serverBootstrap() {
        return serverBootstrap;
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
     * ServerBuilder builder = new ServerBuilder();
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

    /**
     * A shortcut to {@link #stop() stop().get()}.
     */
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

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("config", config())
                          .add("activePorts", activePorts())
                          .add("state", startStop)
                          .toString();
    }

    private final class ServerStartStopSupport extends StartStopSupport<Void, ServerListener> {

        private volatile GracefulShutdownSupport gracefulShutdownSupport = GracefulShutdownSupport.disabled();

        ServerStartStopSupport(Executor startStopExecutor) {
            super(startStopExecutor);
        }

        @Override
        protected CompletionStage<Void> doStart() {
            if (config().gracefulShutdownQuietPeriod().isZero()) {
                gracefulShutdownSupport = GracefulShutdownSupport.disabled();
            } else {
                gracefulShutdownSupport =
                        GracefulShutdownSupport.create(config().gracefulShutdownQuietPeriod(),
                                                       config().blockingTaskExecutor());
            }

            // Initialize the server sockets asynchronously.
            final CompletableFuture<Void> future = new CompletableFuture<>();
            final List<ServerPort> ports = config().ports();
            final AtomicInteger remainingPorts = new AtomicInteger(ports.size());
            for (final ServerPort p: ports) {
                doStart(p).addListener(new ServerPortStartListener(remainingPorts, future, p));
            }

            setupServerMetrics();
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
                @SuppressWarnings("unchecked")
                final ChannelOption<Object> castOption = (ChannelOption<Object>) k;
                b.childOption(castOption, v);
            });

            b.group(EventLoopGroups.newEventLoopGroup(1, r -> {
                final FastThreadLocalThread thread = new FastThreadLocalThread(r, bossThreadName(port));
                thread.setDaemon(false);
                return thread;
            }), config.workerGroup());
            b.channel(TransportType.detectTransportType().serverChannelClass());
            b.handler(connectionLimitingHandler);
            b.childHandler(new HttpServerPipelineConfigurator(config, port, sslContexts,
                                                              gracefulShutdownSupport));

            return b.bind(port.localAddress());
        }

        private void setupServerMetrics() {
            final MeterRegistry meterRegistry = config().meterRegistry();
            meterRegistry.gauge("armeria.server.pendingResponses", gracefulShutdownSupport,
                                GracefulShutdownSupport::pendingResponses);
            meterRegistry.gauge("armeria.server.connections", connectionLimitingHandler,
                                ConnectionLimitingHandler::numConnections);
        }

        @Override
        protected CompletionStage<Void> doStop() {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            final GracefulShutdownSupport gracefulShutdownSupport = this.gracefulShutdownSupport;
            if (gracefulShutdownSupport.completedQuietPeriod()) {
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
            ChannelUtil.close(serverChannels).whenComplete((unused1, unused2) -> {
                // All server ports have been closed.
                primaryActivePort = null;
                activePorts.clear();

                // Close all accepted sockets.
                ChannelUtil.close(connectionLimitingHandler.children()).whenComplete((unused3, unused4) -> {
                    // Shut down the worker group if necessary.
                    final Future<?> workerShutdownFuture;
                    if (config.shutdownWorkerGroupOnStop()) {
                        workerShutdownFuture = config.workerGroup().shutdownGracefully();
                    } else {
                        workerShutdownFuture = ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
                    }

                    workerShutdownFuture.addListener(unused5 -> {
                        // If starts to shutdown before initializing serverChannels, completes the future
                        // immediately.
                        if (serverChannels.size() == 0) {
                            finishDoStop(future);
                            return;
                        }
                        // Shut down all boss groups and wait until they are terminated.
                        final AtomicInteger remainingBossGroups = new AtomicInteger(serverChannels.size());
                        serverChannels.forEach(ch -> {
                            final EventLoopGroup bossGroup = ch.eventLoop().parent();
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
                });
            });
        }

        private void finishDoStop(CompletableFuture<Void> future) {
            // TODO(trustin): Add shutdownBlockingTaskExecutorOnStop
            // TODO(trustin): Count the pending blocking tasks and wait until it goes zero.
            if (!config.shutdownAccessLogWriterOnStop()) {
                future.complete(null);
                return;
            }

            config.accessLogWriter().shutdown().exceptionally(cause -> {
                logger.warn("Failed to close the {}:", AccessLogWriter.class.getSimpleName(), cause);
                return null;
            }).thenRunAsync(() -> future.complete(null), config.startStopExecutor());
        }

        @Override
        protected void notifyStarting(ServerListener listener) throws Exception {
            listener.serverStarting(Server.this);
        }

        @Override
        protected void notifyStarted(ServerListener listener, @Nullable Void value) throws Exception {
            listener.serverStarted(Server.this);
        }

        @Override
        protected void notifyStopping(ServerListener listener) throws Exception {
            listener.serverStopping(Server.this);
        }

        @Override
        protected void notifyStopped(ServerListener listener) throws Exception {
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

    private final class ServerPortStartListener implements ChannelFutureListener {

        private final AtomicInteger remainingPorts;
        private final CompletableFuture<Void> startFuture;
        private final ServerPort port;

        ServerPortStartListener(
                AtomicInteger remainingPorts, CompletableFuture<Void> startFuture, ServerPort port) {

            this.remainingPorts = requireNonNull(remainingPorts, "remainingPorts");
            this.startFuture = requireNonNull(startFuture, "startFuture");
            this.port = requireNonNull(port, "port");
        }

        @Override
        public void operationComplete(ChannelFuture f) {
            final Channel ch = f.channel();
            assert ch.eventLoop().inEventLoop();

            if (startFuture.isDone()) {
                return;
            }

            if (f.isSuccess()) {
                serverChannels.add(ch);
                ch.closeFuture()
                  .addListener((ChannelFutureListener) future -> serverChannels.remove(future.channel()));

                final InetSocketAddress localAddress = (InetSocketAddress) ch.localAddress();
                final ServerPort actualPort = new ServerPort(localAddress, port.protocols());

                // Update the boss thread so its name contains the actual port.
                Thread.currentThread().setName(bossThreadName(actualPort));

                // Update the map of active ports.
                activePorts.put(localAddress, actualPort);

                // The port that has been activated first becomes the primary port.
                if (primaryActivePort == null) {
                    primaryActivePort = actualPort;
                }

                if (logger.isInfoEnabled()) {
                    if (localAddress.getAddress().isAnyLocalAddress() ||
                        localAddress.getAddress().isLoopbackAddress()) {
                        port.protocols().forEach(p -> logger.info(
                                "Serving {} at {} - {}://127.0.0.1:{}/",
                                p.name(), localAddress, p.uriText(), localAddress.getPort()));
                    } else {
                        logger.info("Serving {} at {}", Joiner.on('+').join(port.protocols()), localAddress);
                    }
                }

                if (remainingPorts.decrementAndGet() == 0) {
                    startFuture.complete(null);
                }
            } else {
                startFuture.completeExceptionally(f.cause());
            }
        }
    }

    private static String bossThreadName(ServerPort port) {
        final InetSocketAddress localAddr = port.localAddress();
        final String localHostName =
                localAddr.getAddress().isAnyLocalAddress() ? "*" : localAddr.getHostString();

        // e.g. 'armeria-boss-http-*:8080'
        //      'armeria-boss-http-127.0.0.1:8443'
        //      'armeria-boss-proxy+http+https-127.0.0.1:8443'
        final String protocolNames = port.protocols().stream()
                                         .map(SessionProtocol::uriText)
                                         .collect(Collectors.joining("+"));
        return "armeria-boss-" + protocolNames + '-' + localHostName + ':' + localAddr.getPort();
    }
}
