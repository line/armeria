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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

/**
 * Builds a new {@link ServerListener}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerListenerBuilder slb = new ServerListenerBuilder();
 * // Add a {@link ServerListener#serverStarting(Server)} callback.
 * slb.addStartingCallback((Server server) -> {...});
 * // Add multiple {@link ServerListener#serverStarted(Server)} callbacks, one by one.
 * slb.addStartedCallback((Server server) -> {...});
 * slb.addStartedCallback((Server server) -> {...});
 * // Add multiple {@link ServerListener#serverStopping(Server)} callbacks at once, with varargs.
 * slb.addStoppingCallbacks(consumer1, consumer2, consumer3);
 * // Add multiple {@link ServerListener#serverStopped(Server)} callbacks at once, with an Iterable.
 * slb.addStoppedCallbacks(consumerIterable);
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
     * Add {@link Runnable} invoked when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    public ServerListenerBuilder addStartingCallback(Runnable runnable) {
        requireNonNull(runnable, "runnable");
        serverStartingCallbacks.add(unused -> runnable.run());
        return this;
    }

    /**
     * Add {@link Consumer} invoked when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    public ServerListenerBuilder addStartingCallback(Consumer<? super Server> consumer) {
        serverStartingCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    @SafeVarargs
    public final ServerListenerBuilder addStartingCallbacks(Consumer<? super Server>... consumers) {
        return addStartingCallbacks(Arrays.asList(consumers));
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    public ServerListenerBuilder addStartingCallbacks(Iterable<Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStartingCallbacks.add(requireNonNull(consumer, "consumer"));
        }
        return this;
    }

    /**
     * Add {@link Runnable} invoked when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    public ServerListenerBuilder addStartedCallback(Runnable runnable) {
        requireNonNull(runnable, "runnable");
        serverStartedCallbacks.add(unused -> runnable.run());
        return this;
    }

    /**
     * Add {@link Consumer} invoked when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    public ServerListenerBuilder addStartedCallback(Consumer<? super Server> consumer) {
        serverStartedCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    @SafeVarargs
    public final ServerListenerBuilder addStartedCallbacks(Consumer<? super Server>... consumers) {
        return addStartedCallbacks(Arrays.asList(consumers));
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    public ServerListenerBuilder addStartedCallbacks(Iterable<Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStartedCallbacks.add(requireNonNull(consumer, "consumer"));
        }
        return this;
    }

    /**
     * Add {@link Runnable} invoked when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    public ServerListenerBuilder addStoppingCallback(Runnable runnable) {
        requireNonNull(runnable, "runnable");
        serverStoppingCallbacks.add(unused -> runnable.run());
        return this;
    }

    /**
     * Add {@link Consumer} invoked when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    public ServerListenerBuilder addStoppingCallback(Consumer<? super Server> consumer) {
        serverStoppingCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    @SafeVarargs
    public final ServerListenerBuilder addStoppingCallbacks(Consumer<? super Server>... consumers) {
        return addStoppingCallbacks(Arrays.asList(consumers));
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    public ServerListenerBuilder addStoppingCallbacks(Iterable<Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStoppingCallbacks.add(requireNonNull(consumer, "consumer"));
        }
        return this;
    }

    /**
     * Add {@link Runnable} invoked when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    public ServerListenerBuilder addStoppedCallback(Runnable runnable) {
        requireNonNull(runnable, "runnable");
        serverStoppedCallbacks.add(unused -> runnable.run());
        return this;
    }

    /**
     * Add {@link Consumer} invoked when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    public ServerListenerBuilder addStoppedCallback(Consumer<? super Server> consumer) {
        serverStoppedCallbacks.add(requireNonNull(consumer, "consumer"));
        return this;
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    @SafeVarargs
    public final ServerListenerBuilder addStoppedCallbacks(Consumer<? super Server>... consumers) {
        return addStoppedCallbacks(Arrays.asList(consumers));
    }

    /**
     * Add {@link Consumer}s invoked when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    public ServerListenerBuilder addStoppedCallbacks(Iterable<Consumer<? super Server>> consumers) {
        requireNonNull(consumers, "consumers");
        for (Consumer<? super Server> consumer : consumers) {
            serverStoppedCallbacks.add(requireNonNull(consumer, "consumer"));
        }
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
