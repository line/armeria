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

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.NativeLibraries;
import com.linecorp.armeria.server.http.HttpServerPipelineConfigurator;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;
import io.netty.util.DomainNameMappingBuilder;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Listens to {@link ServerPort}s and delegates client requests to {@link Service}s.
 *
 * @see ServerBuilder
 */
public final class Server implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private final ServerConfig config;
    private final DomainNameMapping<SslContext> sslContexts;

    private final StateManager stateManager = new StateManager();
    private final Map<InetSocketAddress, ServerPort> activePorts = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, ServerPort> unmodifiableActivePorts = Collections.unmodifiableMap(activePorts);

    private final List<ServerListener> listeners = new CopyOnWriteArrayList<>();

    private volatile ServerPort primaryActivePort;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;

    /**
     * A handler that is shared by all ports and channels to be able to keep
     * track of all requests being processed by the server.
     */
    private volatile GracefulShutdownHandler gracefulShutdownHandler;

    Server(ServerConfig config) {
        this.config = requireNonNull(config, "config");
        config.setServer(this);

        // Pre-populate the domain name mapping for later matching.
        SslContext lastSslContext = null;
        for (VirtualHost h: config.virtualHosts()) {
            lastSslContext = h.sslContext();
        }

        if (lastSslContext == null) {
            sslContexts = null;
            for (ServerPort p: config.ports()) {
                if (p.protocol().isTls()) {
                    throw new IllegalArgumentException("no SSL context specified");
                }
            }
        } else {
            final DomainNameMappingBuilder<SslContext>
                    mappingBuilder = new DomainNameMappingBuilder<>(lastSslContext);
            for (VirtualHost h : config.virtualHosts()) {
                final SslContext sslCtx = h.sslContext();
                if (sslCtx != null) {
                    mappingBuilder.add(h.hostnamePattern(), sslCtx);
                }
            }
            sslContexts = mappingBuilder.build();
        }

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

    /**
     * Adds the specified {@link ServerListener} to this {@link Server}, so that it is notified when the state
     * of this {@link Server} changes. This method is useful when you want to initialize/destroy the resources
     * associated with a {@link Service}:
     * <pre>{@code
     * public class MyService extends SimpleService {
     *     &#64;Override
     *     public void serviceAdded(Server server) {
     *         server.addListener(new ServerListenerAdapter() {
     *             &#64;Override
     *             public void serverStarting() {
     *                 ... initialize ...
     *             }
     *
     *             &#64;Override
     *             public void serverStopped() {
     *                 ... destroy ...
     *             }
     *         }
     *     }
     * }
     * }</pre>
     */
    public void addListener(ServerListener listener) {
        listeners.add(requireNonNull(listener, "listener"));
    }

    /**
     * Removes the specified {@link ServerListener} from this {@link Server}, so that it is not notified
     * anymore when the state of this {@link Server} changes.
     */
    public boolean removeListener(ServerListener listener) {
        return listeners.remove(requireNonNull(listener, "listener"));
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
        final CompletableFuture<Void> future = new CompletableFuture<>();
        start(future);
        return future;
    }

    private void start(CompletableFuture<Void> future) {
        final State state = stateManager.state();
        switch (state.type) {
        case STOPPING:
            // A user called start() to restart the server, but the server is not stopped completely.
            // Try again after stopping.
            state.future.handle(voidFunction((ret, cause) -> start(future)))
                        .exceptionally(CompletionActions::log);
            return;
        }

        if (!stateManager.enterStarting(future, this::stop0)) {
            assert future.isCompletedExceptionally();
            return;
        }

        try {
            // Initialize the event loop groups.
            if (NativeLibraries.isEpollAvailable()) {
                final ThreadFactory bossThreadFactory = new DefaultThreadFactory("armeria-server-boss-epoll", false);
                final ThreadFactory workerThreadFactory = new DefaultThreadFactory("armeria-server-epoll", false);
                bossGroup = new EpollEventLoopGroup(1, bossThreadFactory);
                workerGroup = new EpollEventLoopGroup(config.numWorkers(), workerThreadFactory);
            } else {
                final ThreadFactory bossThreadFactory = new DefaultThreadFactory("armeria-server-boss-nio", false);
                final ThreadFactory workerThreadFactory = new DefaultThreadFactory("armeria-server-nio", false);
                bossGroup = new NioEventLoopGroup(1, bossThreadFactory);
                workerGroup = new NioEventLoopGroup(config.numWorkers(), workerThreadFactory);
            }

            // Initialize the server sockets asynchronously.
            final List<ServerPort> ports = config().ports();
            final AtomicInteger remainingPorts = new AtomicInteger(ports.size());
            if (config().gracefulShutdownQuietPeriod().isZero()) {
                gracefulShutdownHandler = null;
            } else {
                gracefulShutdownHandler =
                        GracefulShutdownHandler.create(config().gracefulShutdownQuietPeriod(),
                                                       config().blockingTaskExecutor());
            }

            for (ServerPort p: ports) {
                start(p).addListener(new ServerPortStartListener(remainingPorts, future, p));
            }
        } catch (Throwable t) {
            completeFutureExceptionally(future, t);
        }
    }

    private ChannelFuture start(ServerPort port) {
        ServerBootstrap b = new ServerBootstrap();

        b.group(bossGroup, workerGroup);
        b.channel(NativeLibraries.isEpollAvailable() ? EpollServerSocketChannel.class
                                                     : NioServerSocketChannel.class);
        b.childHandler(new HttpServerPipelineConfigurator(config, port, sslContexts,
                                                          Optional.ofNullable(gracefulShutdownHandler)));

        return b.bind(port.localAddress());
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
        final CompletableFuture<Void> future = new CompletableFuture<>();
        stop(future);
        return future;
    }

    private void stop(CompletableFuture<Void> future) {
        for (;;) {
            final State state = stateManager.state();
            switch (state.type) {
            case STOPPED:
                completeFuture(future);
                return;
            case STOPPING:
                state.future.handle(voidFunction((ret, cause) -> {
                    if (cause == null) {
                        completeFuture(future);
                    } else {
                        completeFutureExceptionally(future, cause);
                    }
                })).exceptionally(CompletionActions::log);
                return;
            case STARTED:
                if (!stateManager.enterStopping(state, future)) {
                    // Someone else changed the state meanwhile; try again.
                    continue;
                }

                stop0(future);
                return;
            case STARTING:
                // Wait until the start process is finished, and then try again.
                state.future.handle(voidFunction((ret, cause) -> stop(future)))
                            .exceptionally(CompletionActions::log);
                return;
            }
        }
    }

    /**
     * Returns a {@link EventLoop} from the worker group. This can be used for, e.g., scheduling background
     * tasks for the lifetime of the {@link Server} using
     * {@link EventLoop#scheduleAtFixedRate(Runnable, long, long, TimeUnit)}. It is very important that these
     * tasks do not block as this would block all requests in the server on that {@link EventLoop}.
     */
    public EventLoop nextEventLoop() {
        if (workerGroup == null) {
            throw new IllegalStateException("nextEventLoop must be called after the server is started.");
        }
        return workerGroup.next();
    }

    private void stop0(CompletableFuture<Void> future) {
        assert future != null;

        final EventLoopGroup bossGroup = this.bossGroup;
        final GracefulShutdownHandler gracefulShutdownHandler = this.gracefulShutdownHandler;

        if (gracefulShutdownHandler == null) {
            stop1(future, bossGroup);
            return;
        }

        // Check every 100 ms for the server to have completed processing
        // requests.
        bossGroup.scheduleAtFixedRate(() -> {
            if (gracefulShutdownHandler.completedQuietPeriod()) {
                stop1(future, bossGroup);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        // Make sure the event loop stops after the timeout, regardless of what
        // the GracefulShutdownHandler says.
        bossGroup.schedule(() -> stop1(future, bossGroup),
                           config.gracefulShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void stop1(CompletableFuture<Void> future, EventLoopGroup bossGroup) {
        // FIXME(trustin): Shutdown and terminate the blockingTaskExecutor.
        //                 Could be fixed while fixing https://github.com/line/armeria/issues/46

        final Future<?> bossShutdownFuture;
        if (bossGroup != null) {
            bossShutdownFuture = bossGroup.shutdownGracefully();
            this.bossGroup = null;
        } else {
            bossShutdownFuture = ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
        }

        bossShutdownFuture.addListener(f1 -> {
            // All server ports have been unbound.
            primaryActivePort = null;
            activePorts.clear();

            // Shut down the workers.
            final EventLoopGroup workerGroup = this.workerGroup;
            final Future<?> workerShutdownFuture;
            if (workerGroup != null) {
                workerShutdownFuture = workerGroup.shutdownGracefully();
                this.workerGroup = null;
            } else {
                workerShutdownFuture = ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
            }

            workerShutdownFuture.addListener(f2 -> {
                stateManager.enter(State.STOPPED);
                completeFuture(future);
            });
        });
    }

    /**
     * A shortcut to {@link #stop() stop().get()}.
     */
    @Override
    public void close() {
        final CompletableFuture<Void> f = stop();
        boolean interrupted = false;
        for (;;) {
            try {
                f.get();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            } catch (ExecutionException e) {
                logger.warn("Failed to stop a server", e);
                break;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    enum StateType {
        STARTING,
        STARTED,
        STOPPING,
        STOPPED
    }

    static final class State {
        static final State STARTED = new State(StateType.STARTED, null);
        static final State STOPPED = new State(StateType.STOPPED, null);

        final StateType type;
        final CompletableFuture<Void> future;

        State(StateType type, CompletableFuture<Void> future) {
            this.type = type;
            this.future = future;
        }

        @Override
        public String toString() {
            return "(" + type + ", " + future + ')';
        }
    }

    private final class StateManager {

        private final AtomicReference<State> ref = new AtomicReference<>(State.STOPPED);

        State state() {
            return ref.get();
        }

        void enter(State state) {
            assert state.type != StateType.STARTING; // must be set via setStarting()
            assert state.type != StateType.STOPPING; // must be set via setStopping()

            ref.set(state);
            notifyState(state);
        }

        boolean enterStarting(CompletableFuture<Void> future, Consumer<CompletableFuture<Void>> rollbackTask) {
            final State startingState = new State(StateType.STARTING, future);
            if (!ref.compareAndSet(State.STOPPED, startingState)) {
                completeFutureExceptionally(
                        future,
                        new IllegalStateException("must be stopped to start: " + this));
                return false;
            }

            // Note that we added the listener only ..
            // - after the compareAndSet() above so that the listener is invoked only when state transition
            //   actually occurred.
            // - before notifyState() below so that the listener is invoked when listener notification fails.

            future.handle(voidFunction((ret, cause) -> {
                if (cause == null) {
                    enter(State.STARTED);
                } else {
                    rollbackTask.accept(stateManager.enterStopping(new CompletableFuture<>()));
                }
            })).exceptionally(CompletionActions::log);

            if (!notifyState(startingState)) {
                completeFutureExceptionally(
                        future,
                        new IllegalStateException("failed to notify all server listeners"));
                return false;
            }

            return true;
        }

        CompletableFuture<Void> enterStopping(CompletableFuture<Void> future) {
            final State update = new State(StateType.STOPPING, future);
            ref.set(update);

            notifyState(update);
            return future;
        }

        boolean enterStopping(State expect, CompletableFuture<Void> future) {
            final State update = new State(StateType.STOPPING, future);
            if (expect != null) {
                if (!ref.compareAndSet(expect, update)) {
                    return false;
                }
            } else {
                ref.set(update);
            }

            notifyState(update);
            return true;
        }

        private boolean notifyState(State state) {
            final AtomicBoolean success = new AtomicBoolean(true);
            listeners.forEach(l -> {
                try {
                    switch (state.type) {
                    case STARTING:
                        l.serverStarting(Server.this);
                        break;
                    case STARTED:
                        l.serverStarted(Server.this);
                        break;
                    case STOPPING:
                        l.serverStopping(Server.this);
                        break;
                    case STOPPED:
                        l.serverStopped(Server.this);
                        break;
                    default:
                        throw new Error("unknown state type " + state.type);
                    }
                } catch (Throwable t) {
                    success.set(false);
                    logger.warn("Failed to notify a server listener: {}", l, t);
                }
            });

            return success.get();
        }

        @Override
        public String toString() {
            return ref.toString();
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
        public void operationComplete(ChannelFuture f) throws Exception {
            if (startFuture.isDone()) {
                return;
            }

            if (f.isSuccess()) {
                InetSocketAddress localAddress = (InetSocketAddress) f.channel().localAddress();
                ServerPort actualPort = new ServerPort(localAddress, port.protocol());

                activePorts.put(localAddress, actualPort);

                // The port that has been activated first becomes the primary port.
                if (primaryActivePort == null) {
                    primaryActivePort = actualPort;
                }

                if (remainingPorts.decrementAndGet() == 0) {
                    completeFuture(startFuture);
                }
            } else {
                completeFutureExceptionally(startFuture, f.cause());
            }
        }
    }

    private static void completeFuture(CompletableFuture<Void> future) {
        if (GlobalEventExecutor.INSTANCE.inEventLoop()) {
            future.complete(null);
        } else {
            GlobalEventExecutor.INSTANCE.execute(() -> future.complete(null));
        }
    }

    private static void completeFutureExceptionally(CompletableFuture<Void> future, Throwable cause) {
        if (GlobalEventExecutor.INSTANCE.inEventLoop()) {
            future.completeExceptionally(cause);
        } else {
            GlobalEventExecutor.INSTANCE.execute(() -> future.completeExceptionally(cause));
        }
    }
}
