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

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.internal.ChannelUtil;
import com.linecorp.armeria.internal.ConnectionLimitingHandler;
import com.linecorp.armeria.internal.TransportType;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;
import io.netty.util.DomainNameMappingBuilder;
import io.netty.util.concurrent.FastThreadLocalThread;
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
    private final Set<Channel> serverChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<InetSocketAddress, ServerPort> activePorts = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, ServerPort> unmodifiableActivePorts =
            Collections.unmodifiableMap(activePorts);

    private final List<ServerListener> listeners = new CopyOnWriteArrayList<>();

    private final ConnectionLimitingHandler connectionLimitingHandler;

    private volatile ServerPort primaryActivePort;

    /**
     * A handler that is shared by all ports and channels to be able to keep
     * track of all requests being processed by the server.
     */
    private volatile GracefulShutdownSupport gracefulShutdownSupport = GracefulShutdownSupport.disabled();

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

        connectionLimitingHandler = new ConnectionLimitingHandler(config.maxNumConnections());
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
            // Initialize the server sockets asynchronously.
            final List<ServerPort> ports = config().ports();
            final AtomicInteger remainingPorts = new AtomicInteger(ports.size());
            if (config().gracefulShutdownQuietPeriod().isZero()) {
                gracefulShutdownSupport = GracefulShutdownSupport.disabled();
            } else {
                gracefulShutdownSupport =
                        GracefulShutdownSupport.create(config().gracefulShutdownQuietPeriod(),
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
        final ServerBootstrap b = new ServerBootstrap();

        b.group(EventLoopGroups.newEventLoopGroup(1, r -> {
            final FastThreadLocalThread thread = new FastThreadLocalThread(r, bossThreadName(port));
            thread.setDaemon(false);
            return thread;
        }), config.workerGroup());
        b.channel(TransportType.detectTransportType().serverChannelClass());
        b.handler(connectionLimitingHandler);
        b.childHandler(new HttpServerPipelineConfigurator(config, port, sslContexts, gracefulShutdownSupport));

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
        return config().workerGroup().next();
    }

    private void stop0(CompletableFuture<Void> future) {
        assert future != null;

        final GracefulShutdownSupport gracefulShutdownSupport = this.gracefulShutdownSupport;
        if (gracefulShutdownSupport.completedQuietPeriod()) {
            stop1(future, null);
            return;
        }

        // Create a single-use thread dedicated for monitoring graceful shutdown status.
        final ScheduledExecutorService gracefulShutdownExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "armeria-shutdown-0x" + Integer.toHexString(hashCode())));

        // Check every 100 ms for the server to have completed processing requests.
        final ScheduledFuture<?> quietPeriodFuture = gracefulShutdownExecutor.scheduleAtFixedRate(() -> {
            if (gracefulShutdownSupport.completedQuietPeriod()) {
                stop1(future, gracefulShutdownExecutor);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        // Make sure the event loop stops after the timeout, regardless of what
        // the GracefulShutdownSupport says.
        try {
            gracefulShutdownExecutor.schedule(() -> {
                quietPeriodFuture.cancel(false);
                stop1(future, gracefulShutdownExecutor);
            }, config.gracefulShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Can be rejected if quiet period is complete already.
        }
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
    private void stop1(CompletableFuture<Void> future, @Nullable ExecutorService gracefulShutdownExecutor) {
        // Graceful shutdown is over. Terminate the temporary executor we created at stop0(future).
        if (gracefulShutdownExecutor != null) {
            gracefulShutdownExecutor.shutdownNow();
        }

        // Close all server sockets.
        Set<Channel> serverChannels = ImmutableSet.copyOf(this.serverChannels);
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
                            // TODO(trustin): Add shutdownBlockingTaskExecutorOnStop
                            // TODO(trustin): Count the pending blocking tasks and wait until it becomes zero.
                            stateManager.enter(State.STOPPED);
                            completeFuture(future);
                        });
                    });
                });
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
                          .add("state", stateManager.state())
                          .toString();
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

            final State oldState = ref.getAndSet(state);
            notifyState(oldState, state);
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

            if (!notifyState(State.STOPPED, startingState)) {
                completeFutureExceptionally(
                        future,
                        new IllegalStateException("failed to notify all server listeners"));
                return false;
            }

            return true;
        }

        CompletableFuture<Void> enterStopping(CompletableFuture<Void> future) {
            final State update = new State(StateType.STOPPING, future);
            final State oldState = ref.getAndSet(update);

            notifyState(oldState, update);
            return future;
        }

        boolean enterStopping(State expect, CompletableFuture<Void> future) {
            final State update = new State(StateType.STOPPING, future);
            final State oldState;
            if (expect != null) {
                if (!ref.compareAndSet(expect, update)) {
                    return false;
                }
                oldState = expect;
            } else {
                oldState = ref.getAndSet(update);
            }

            notifyState(oldState, update);
            return true;
        }

        private boolean notifyState(State oldState, State state) {
            if (oldState.type == state.type) {
                return true;
            }

            boolean success = true;
            for (ServerListener l : listeners) {
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
                    success = false;
                    logger.warn("Failed to notify a server listener: {}", l, t);
                }
            }

            return success;
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
                final ServerPort actualPort = new ServerPort(localAddress, port.protocol());

                // Update the boss thread so its name contains the actual port.
                Thread.currentThread().setName(bossThreadName(actualPort));

                // Update the map of active ports.
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

    private static String bossThreadName(ServerPort port) {
        final InetSocketAddress localAddr = port.localAddress();
        final String localHostName =
                localAddr.getAddress().isAnyLocalAddress() ? "*" : localAddr.getHostString();

        // e.g. 'armeria-boss-http-*:8080'
        //      'armeria-boss-http-127.0.0.1:8443'
        return "armeria-boss-" + port.protocol().uriText() + '-' + localHostName + ':' + localAddr.getPort();
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
