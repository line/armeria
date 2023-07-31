/*
 * Copyright 2018 LINE Corporation
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

/**
 * Builds a new {@link ServerListener}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerListenerBuilder slb = ServerListener.builder();
 * // Add a {@link ServerListener#serverStarting(Server)} callback.
 * slb.whenStarting((Server server) -> {...});
 * // Add multiple {@link ServerListener#serverStarted(Server)} callbacks, one by one.
 * slb.whenStarted((Server server) -> {...});
 * slb.whenStarted((Server server) -> {...});
 * // Add multiple {@link ServerListener#serverStopping(Server)} callbacks at once, with varargs.
 * slb.whenStopping(consumer1, consumer2, consumer3);
 * // Add multiple {@link ServerListener#serverStopped(Server)} callbacks at once, with an Iterable.
 * slb.whenStopped(consumerIterable);
 * // Build a `ServerListener` instance.
 * ServerListener sl = slb.build();
 * // Set to `Server`.
 * Server server = ...
 * server.serverListener(sl);
 * }</pre>
 * */
public final class ServerListenerBuilder {

    /**
     * {@link Consumer}s invoked when the {@link Server} is starting.
     * */
    private final List<Consumer<? super Server>> serverStartingCallbacks = new ArrayList<>();

    /**
     * {@link Consumer}s invoked when the {@link Server} is started.
     * */
    private final List<Consumer<? super Server>> serverStartedCallbacks = new ArrayList<>();

    /**
     * {@link Consumer}s invoked when the {@link Server} is stopping.
     * */
    private final List<Consumer<? super Server>> serverStoppingCallbacks = new ArrayList<>();

    /**
     * {@link Consumer}s invoked when the {@link Server} is stopped.
     * */
    private final List<Consumer<? super Server>> serverStoppedCallbacks = new ArrayList<>();

    ServerListenerBuilder() {}

    private static class CallbackServerListener implements ServerListener {
        /**
         * {@link Consumer}s invoked when the {@link Server} is starting.
         * */
        private final List<Consumer<? super Server>> serverStartingCallbacks;

        /**
         * {@link Consumer}s invoked when the {@link Server} is started.
         * */
        private final List<Consumer<? super Server>> serverStartedCallbacks;

        /**
         * {@link Consumer}s invoked when the {@link Server} is stopping.
         * */
        private final List<Consumer<? super Server>> serverStoppingCallbacks;

        /**
         * {@link Consumer}s invoked when the {@link Server} is stopped.
         * */
        private final List<Consumer<? super Server>> serverStoppedCallbacks;

        CallbackServerListener(List<Consumer<? super Server>> serverStartingCallbacks,
                               List<Consumer<? super Server>> serverStartedCallbacks,
                               List<Consumer<? super Server>> serverStoppingCallbacks,
                               List<Consumer<? super Server>> serverStoppedCallbacks) {
            this.serverStartingCallbacks = ImmutableList.copyOf(serverStartingCallbacks);
            this.serverStartedCallbacks = ImmutableList.copyOf(serverStartedCallbacks);
            this.serverStoppingCallbacks = ImmutableList.copyOf(serverStoppingCallbacks);
            this.serverStoppedCallbacks = ImmutableList.copyOf(serverStoppedCallbacks);
        }

        @Override
        public void serverStarting(Server server) {
            for (Consumer<? super Server> serverStartingCallback : serverStartingCallbacks) {
                serverStartingCallback.accept(server);
            }
        }

        @Override
        public void serverStarted(Server server) {
            for (Consumer<? super Server> serverStartedCallback : serverStartedCallbacks) {
                serverStartedCallback.accept(server);
            }
        }

        @Override
        public void serverStopping(Server server) {
            for (Consumer<? super Server> serverStoppingCallback : serverStoppingCallbacks) {
                serverStoppingCallback.accept(server);
            }
        }

        @Override
        public void serverStopped(Server server) {
            for (Consumer<? super Server> serverStoppedCallback : serverStoppedCallbacks) {
                serverStoppedCallback.accept(server);
            }
        }
    }

    /**
     * Adds a {@link Consumer} invoked when the {@link Server} is starting.
     *
     * @see ServerListener#serverStarting(Server)
     */
    public ServerListenerBuilder whenStarting(Consumer<? super Server> consumer) {
        serverStartingCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Adds {@link Consumer}s invoked when the {@link Server} is starting.
     *
     * @see ServerListener#serverStarting(Server)
     */
    @SafeVarargs
    public final ServerListenerBuilder whenStarting(Consumer<? super Server>... consumers) {
        return whenStarting(Arrays.asList(consumers));
    }

    /**
     * Adds {@link Consumer}s invoked when the {@link Server} is starting.
     *
     * @see ServerListener#serverStarting(Server)
     */
    public ServerListenerBuilder whenStarting(Iterable<? extends Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStartingCallbacks.add(requireNonNull(consumer, "consumer"));
        }
        return this;
    }

    /**
     * Adds a {@link Consumer} invoked when the {@link Server} is started.
     *
     * @see ServerListener#serverStarted(Server)
     */
    public ServerListenerBuilder whenStarted(Consumer<? super Server> consumer) {
        serverStartedCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Adds {@link Consumer}s invoked when the {@link Server} is started.
     *
     * @see ServerListener#serverStarted(Server)
     */
    @SafeVarargs
    public final ServerListenerBuilder whenStarted(Consumer<? super Server>... consumers) {
        return whenStarted(Arrays.asList(consumers));
    }

    /**
     * Adds {@link Consumer}s invoked when the {@link Server} is started.
     *
     * @see ServerListener#serverStarted(Server)
     */
    public ServerListenerBuilder whenStarted(Iterable<? extends Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStartedCallbacks.add(requireNonNull(consumer, "consumer"));
        }
        return this;
    }

    /**
     * Adds a {@link Consumer} invoked when the {@link Server} is stopping.
     *
     * @see ServerListener#serverStopping(Server)
     */
    public ServerListenerBuilder whenStopping(Consumer<? super Server> consumer) {
        serverStoppingCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopping.
     *
     * @see ServerListener#serverStopping(Server)
     */
    @SafeVarargs
    public final ServerListenerBuilder whenStopping(Consumer<? super Server>... consumers) {
        return whenStopping(Arrays.asList(consumers));
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopping.
     *
     * @see ServerListener#serverStopping(Server)
     */
    public ServerListenerBuilder whenStopping(Iterable<? extends Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStoppingCallbacks.add(requireNonNull(consumer, "consumer"));
        }
        return this;
    }

    /**
     * Add a {@link Consumer} invoked when the {@link Server} is stopped.
     *
     * @see ServerListener#serverStopped(Server)
     */
    public ServerListenerBuilder whenStopped(Consumer<? super Server> consumer) {
        serverStoppedCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopped.
     *
     * @see ServerListener#serverStopped(Server)
     */
    @SafeVarargs
    public final ServerListenerBuilder whenStopped(Consumer<? super Server>... consumers) {
        return whenStopped(Arrays.asList(consumers));
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopped.
     *
     * @see ServerListener#serverStopped(Server)
     */
    public ServerListenerBuilder whenStopped(Iterable<? extends Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStoppedCallbacks.add(requireNonNull(consumer, "consumer"));
        }
        return this;
    }

    /**
     * Add a callback that gracefully shuts down the given {@link ExecutorService} when the {@link Server}
     * is stopping. It will wait indefinitely for the {@link ExecutorService} to terminate during shutdown.
     */
    public ServerListenerBuilder stoppingWithExecutor(ExecutorService executorService) {
        requireNonNull(executorService, "executorService");
        serverStoppingCallbacks.add(s -> ShutdownSupport.of(executorService).shutdown());
        return this;
    }

    /**
     * Add a callback that gracefully shuts down the given {@link ExecutorService} when the {@link Server}
     * is stopping. It allows a maximum duration of {@code terminationTimeout} for the {@link ExecutorService}
     * to terminate gracefully before it is forcefully terminated.
     */
    public ServerListenerBuilder stoppingWithExecutor(ExecutorService executorService,
                                                      Duration terminationTimeout) {
        requireNonNull(executorService, "executorService");
        requireNonNull(terminationTimeout, "terminationTimeout");
        long nanoSeconds;
        try {
            nanoSeconds = terminationTimeout.toNanos();
        } catch (ArithmeticException ignore) {
            nanoSeconds = terminationTimeout.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        stoppingWithExecutor(executorService, nanoSeconds, TimeUnit.NANOSECONDS);
        return this;
    }

    /**
     * Add a callback that gracefully shuts down the given {@link ExecutorService} when the {@link Server}
     * is stopping. It allows a maximum duration of {@code terminationTimeout} in the specified
     * {@code timeUnit} for the {@link ExecutorService} to terminate gracefully before it is forcefully
     * terminated.
     */
    public ServerListenerBuilder stoppingWithExecutor(ExecutorService executorService, long terminationTimeout,
                                                      TimeUnit timeUnit) {
        requireNonNull(executorService, "executorService");
        requireNonNull(timeUnit, "timeUnit");
        serverStoppingCallbacks.add(
                s -> ShutdownSupport.of(executorService, terminationTimeout, timeUnit).shutdown());
        return this;
    }

    /**
     * Add a callback that gracefully shuts down the given {@link ExecutorService} when the {@link Server}
     * is stopping. It uses the specified {@code shutdownStrategy} to control the shutdown behavior.
     */
    public ServerListenerBuilder stoppingWithExecutor(ExecutorService executorService,
                                                      Consumer<ExecutorService> shutdownStrategy) {
        requireNonNull(executorService, "executorService");
        requireNonNull(shutdownStrategy, "shutdownStrategy");
        serverStoppingCallbacks.add(s -> ShutdownSupport.of(executorService, shutdownStrategy).shutdown());
        return this;
    }

    /**
     * Returns a newly-created {@link ServerListener} based on the {@link Runnable}s added to this builder.
     */
    public ServerListener build() {
        return new CallbackServerListener(serverStartingCallbacks, serverStartedCallbacks,
                                          serverStoppingCallbacks, serverStoppedCallbacks);
    }
}
