/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.spring;

import org.springframework.context.SmartLifecycle;

import com.linecorp.armeria.server.Server;

/**
 * Make Armeria {@link Server} utilize spring's SmartLifecycle feature.
 * So Armeria will shutdown before other web servers and beans in the context.
 */
public final class ArmeriaServerGracefulShutdownLifecycle implements SmartLifecycle {
    /**
     * {@link Server} created by {@link ArmeriaAutoConfiguration}. .
     */
    private final Server server;

    /**
     * Creates a new instance.
     */
    public ArmeriaServerGracefulShutdownLifecycle(Server server) {
        this.server = server;
    }

    /**
     * Start this component.
     * Currently AbstractArmeriaAutoConfiguration help starting the server.
     */
    @Override
    public void start() {
    }

    /**
     * Stop this component. This class implements {@link SmartLifecycle}, so don't need to support sync stop.
     */
    @Override
    public void stop() {
        throw new UnsupportedOperationException("Stop must not be invoked directly");
    }

    /**
     * Stop this component.
     */
    @Override
    public void stop(Runnable callback) {
        server.stop().whenComplete((unused, throwable) -> callback.run());
    }

    /**
     * Returns the phase that this lifecycle object is supposed to run in.
     * WebServerStartStopLifecycle's phase is Integer.MAX_VALUE - 1.
     * To run before the tomcat, we need to larger than Integer.MAX_VALUE - 1.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * Check whether this component is currently running.
     */
    @Override
    public boolean isRunning() {
        return !server.isClosed() && !server.isClosing();
    }

    /**
     * Returns true if this Lifecycle component should get started automatically by the container at the time
     * that the containing ApplicationContext gets refreshed.
     * AbstractArmeriaAutoConfiguration start the server manually, so this implementation return false.
     */
    @Override
    public boolean isAutoStartup() {
        return false;
    }
}
