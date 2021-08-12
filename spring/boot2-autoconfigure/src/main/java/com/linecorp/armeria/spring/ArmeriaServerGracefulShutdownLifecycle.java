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

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.linecorp.armeria.server.Server;

/**
 * Make Armeria {@link Server} utilize spring's SmartLifecycle feature.
 * So Armeria will shutdown before other web servers and beans in the context.
 */
final class ArmeriaServerGracefulShutdownLifecycle implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerGracefulShutdownLifecycle.class);

    private final Server server;
    private volatile boolean running;

    ArmeriaServerGracefulShutdownLifecycle(Server server) {
        this.server = requireNonNull(server, "server");
    }

    /**
     * Start this component.
     */
    @Override
    public void start() {
        running = true;
        server.start().handle((result, t) -> {
            if (t != null) {
                throw new IllegalStateException("Armeria server failed to start", t);
            }
            return result;
        }).join();
        logger.info("Armeria server started at ports: {}", server.activePorts());
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
        running = false;
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
        return running;
    }

    /**
     * Returns true if this Lifecycle component should get started automatically by the container at the time
     * that the containing ApplicationContext gets refreshed.
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
