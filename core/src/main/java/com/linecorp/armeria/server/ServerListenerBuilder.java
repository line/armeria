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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds a new {@link ServerListener}.
 * */
public class ServerListenerBuilder {

    /**
     * {@link Runnable}s invoked on when the {@link Server} is starting.
     * */
    private List<Runnable> onStartings = new ArrayList<>();

    /**
     * {@link Runnable}s invoked on when the {@link Server} is started.
     * */
    private List<Runnable> onStarteds = new ArrayList<>();

    /**
     * {@link Runnable}s invoked on when the {@link Server} is stopping.
     * */
    private List<Runnable> onStoppings = new ArrayList<>();

    /**
     * {@link Runnable}s invoked on when the {@link Server} is stopped.
     * */
    private List<Runnable> onStoppeds = new ArrayList<>();

    /**
     * Set {@link Runnable}s invoked on when the {@link Server} is starting.
     * (see: {@link ServerListener#serverStarting(Server)})
     */
    public ServerListenerBuilder onStarting(Runnable... runnables) {
        onStartings = Arrays.asList(runnables);
        return this;
    }

    /**
     * Set {@link Runnable}s invoked on when the {@link Server} is started.
     * (see: {@link ServerListener#serverStarted(Server)})
     */
    public ServerListenerBuilder onStarted(Runnable... runnables) {
        onStarteds = Arrays.asList(runnables);
        return this;
    }

    /**
     * Set {@link Runnable}s invoked on when the {@link Server} is stopping.
     * (see: {@link ServerListener#serverStopping(Server)})
     */
    public ServerListenerBuilder onStopping(Runnable... runnables) {
        onStoppings = Arrays.asList(runnables);
        return this;
    }

    /**
     * Set {@link Runnable}s invoked on when the {@link Server} is stopped.
     * (see: {@link ServerListener#serverStopped(Server)})
     */
    public ServerListenerBuilder onStopped(Runnable... runnables) {
        onStoppeds = Arrays.asList(runnables);
        return this;
    }

    /**
     * Returns a newly-created {@link ServerListener} based on the {@link Runnable}s added to this builder.
     */
    public ServerListener build() {
        return new ServerListener() {

            @Override
            public void serverStarting(Server server) {
                for (Runnable onStarting : onStartings) {
                    onStarting.run();
                }
            }

            @Override
            public void serverStarted(Server server) {
                for (Runnable onStarted : onStarteds) {
                    onStarted.run();
                }
            }

            @Override
            public void serverStopping(Server server) {
                for (Runnable onStopping : onStoppings) {
                    onStopping.run();
                }
            }

            @Override
            public void serverStopped(Server server) {
                for (Runnable onStopped : onStoppeds) {
                    onStopped.run();
                }
            }
        };
    }
}
