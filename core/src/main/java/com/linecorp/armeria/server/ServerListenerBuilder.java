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
import java.util.List;

import com.google.common.collect.ImmutableList;

/**
 * Builds a new {@link ServerListener}.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerListenerBuilder slb = new ServerListenerBuilder();
 * // Add a {@link ServerListener#serverStarting(Server)} callback.
 * slb.addStartingCallback(() -> {...});
 * // Add multiple {@link ServerListener#serverStarted(Server)} callbacks, one by one.
 * slb.addStartedCallback(() -> {...});
 * slb.addStartedCallback(() -> {...});
 * // Add multiple {@link ServerListener#serverStopping(Server)} callbacks at once, with varargs api.
 * slb.addStoppingCallbacks(runnable1, runnable2, runnable3);
 * // Add multiple {@link ServerListener#serverStopped(Server)} callbacks at once, with iterable api.
 * slb.addStoppedCallbacks(runnableIterable);
 * // Build a `ServerListener` instance.
 * ServerListener sl = slb.build();
 * // Set to `Server`.
 * Server server = ...
 * server.serverListener(sl);
 * }</pre>
 * */
public class ServerListenerBuilder {

    /**
     * {@link Runnable}s invoked when the {@link Server} is starting.
     * */
    private final List<Runnable> serverStartingCallbacks = new ArrayList<>();

    /**
     * {@link Runnable}s invoked when the {@link Server} is started.
     * */
    private final List<Runnable> serverStartedCallbacks = new ArrayList<>();

    /**
     * {@link Runnable}s invoked when the {@link Server} is stopping.
     * */
    private final List<Runnable> serverStoppingCallbacks = new ArrayList<>();

    /**
     * {@link Runnable}s invoked when the {@link Server} is stopped.
     * */
    private final List<Runnable> serverStoppedCallbacks = new ArrayList<>();

    private static class CallbackServerListener implements ServerListener {
        /**
         * {@link Runnable}s invoked when the {@link Server} is starting.
         * */
        private final List<Runnable> serverStartingCallbacks;

        /**
         * {@link Runnable}s invoked when the {@link Server} is started.
         * */
        private final List<Runnable> serverStartedCallbacks;

        /**
         * {@link Runnable}s invoked when the {@link Server} is stopping.
         * */
        private final List<Runnable> serverStoppingCallbacks;

        /**
         * {@link Runnable}s invoked when the {@link Server} is stopped.
         * */
        private final List<Runnable> serverStoppedCallbacks;

        CallbackServerListener(List<Runnable> serverStartingCallbacks,
                               List<Runnable> serverStartedCallbacks,
                               List<Runnable> serverStoppingCallbacks,
                               List<Runnable> serverStoppedCallbacks) {
            this.serverStartingCallbacks = ImmutableList.copyOf(serverStartingCallbacks);
            this.serverStartedCallbacks = ImmutableList.copyOf(serverStartedCallbacks);
            this.serverStoppingCallbacks = ImmutableList.copyOf(serverStoppingCallbacks);
            this.serverStoppedCallbacks = ImmutableList.copyOf(serverStoppedCallbacks);
        }

        @Override
        public void serverStarting(Server server) {
            serverStartingCallbacks.forEach(Runnable::run);
        }

        @Override
        public void serverStarted(Server server) {
            serverStartedCallbacks.forEach(Runnable::run);
        }

        @Override
        public void serverStopping(Server server) {
            serverStoppingCallbacks.forEach(Runnable::run);
        }

        @Override
        public void serverStopped(Server server) {
            serverStoppedCallbacks.forEach(Runnable::run);
        }
    }

    /**
     * Add {@link Runnable} invoked when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    public ServerListenerBuilder addStartingCallback(Runnable runnable) {
        serverStartingCallbacks.add(requireNonNull(runnable, "runnable"));
        return this;
    }

    /**
     * Add {@link Runnable}s invoked when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    public ServerListenerBuilder addStartingCallbacks(Runnable... runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStartingCallbacks.add(requireNonNull(runnable));
        }
        return this;
    }

    /**
     * Add {@link Runnable}s invoked when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    public ServerListenerBuilder addStartingCallbacks(Iterable<Runnable> runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStartingCallbacks.add(requireNonNull(runnable));
        }
        return this;
    }

    /**
     * Add {@link Runnable} invoked when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    public ServerListenerBuilder addStartedCallback(Runnable runnable) {
        serverStartedCallbacks.add(requireNonNull(runnable, "runnable"));
        return this;
    }

    /**
     * Add {@link Runnable}s invoked when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    public ServerListenerBuilder addStartedCallbacks(Runnable... runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStartedCallbacks.add(requireNonNull(runnable));
        }
        return this;
    }

    /**
     * Add {@link Runnable}s invoked when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    public ServerListenerBuilder addStartedCallbacks(Iterable<Runnable> runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStartedCallbacks.add(requireNonNull(runnable));
        }
        return this;
    }

    /**
     * Add {@link Runnable} invoked when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    public ServerListenerBuilder addStoppingCallback(Runnable runnable) {
        serverStoppingCallbacks.add(requireNonNull(runnable, "runnable"));
        return this;
    }

    /**
     * Add {@link Runnable}s invoked when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    public ServerListenerBuilder addStoppingCallbacks(Runnable... runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStoppingCallbacks.add(requireNonNull(runnable));
        }
        return this;
    }

    /**
     * Add {@link Runnable}s invoked when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    public ServerListenerBuilder addStoppingCallbacks(Iterable<Runnable> runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStoppingCallbacks.add(requireNonNull(runnable));
        }
        return this;
    }

    /**
     * Add {@link Runnable} invoked on when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    public ServerListenerBuilder addStoppedCallbacks(Runnable runnable) {
        serverStoppedCallbacks.add(requireNonNull(runnable, "runnable"));
        return this;
    }

    /**
     * Add {@link Runnable}s invoked on when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    public ServerListenerBuilder addStoppedCallbacks(Runnable... runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStoppedCallbacks.add(requireNonNull(runnable));
        }
        return this;
    }

    /**
     * Add {@link Runnable}s invoked on when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    public ServerListenerBuilder addStoppedCallbacks(Iterable<Runnable> runnables) {
        requireNonNull(runnables, "runnables");
        for (Runnable runnable : runnables) {
            serverStoppedCallbacks.add(requireNonNull(runnable));
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
