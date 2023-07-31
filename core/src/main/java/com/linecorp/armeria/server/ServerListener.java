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

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Listens to life cycle events of a {@link Server}.
 *
 * @see Server#addListener(ServerListener)
 * @see Server#removeListener(ServerListener)
 */
public interface ServerListener {

    /**
     * Returns a new {@link ServerListenerBuilder}.
     */
    static ServerListenerBuilder builder() {
        return new ServerListenerBuilder();
    }

    /**
     * Creates a new {@link ServerListener} instance with the provided {@link ExecutorService}. The returned
     * {@link ServerListener} will gracefully shut down the given {@link ExecutorService} when the
     * {@link Server} is stopping. It will wait indefinitely for the {@link ExecutorService} to terminate
     * during shutdown.
     */
    static ServerListener withExecutor(ExecutorService executorService) {
        return builder().stoppingWithExecutor(executorService).build();
    }

    /**
     * Creates a new {@link ServerListener} instance with the provided {@link ExecutorService}. The returned
     * {@link ServerListener} will gracefully shut down the given {@link ExecutorService} when the
     * {@link Server} is stopping. It allows a maximum duration of {@code terminationTimeout} for the
     * {@link ExecutorService} to terminate gracefully before it is forcefully terminated.
     */
    static ServerListener withExecutor(ExecutorService executorService, Duration terminationTimeout) {
        return builder().stoppingWithExecutor(executorService, terminationTimeout).build();
    }

    /**
     * Creates a new {@link ServerListener} instance with the provided {@link ExecutorService}. The returned
     * {@link ServerListener} will gracefully shut down the given {@link ExecutorService} when the
     * {@link Server} is stopping. It allows a maximum duration of {@code terminationTimeout} in the specified
     * {@code timeUnit} for the {@link ExecutorService} to terminate gracefully before it is forcefully
     * terminated.
     */
    static ServerListener withExecutor(ExecutorService executorService, long terminationTimeout,
                                       TimeUnit timeUnit) {
        return builder().stoppingWithExecutor(executorService, terminationTimeout, timeUnit).build();
    }

    /**
     * Creates a new {@link ServerListener} instance with the provided {@link ExecutorService}. The returned
     * {@link ServerListener} will gracefully shut down the given {@link ExecutorService} when the
     * {@link Server} is stopping. It uses the specified {@code shutdownStrategy} to control the shutdown
     * behavior.
     */
    static ServerListener withExecutor(ExecutorService executorService,
                                       Consumer<ExecutorService> shutdownStrategy) {
        return builder().stoppingWithExecutor(executorService, shutdownStrategy).build();
    }

    /**
     * Invoked when a {@link Server} begins its startup procedure. Note that the {@link Server} will abort
     * its startup when a {@link ServerListener#serverStarting(Server)} throws an exception.
     */
    void serverStarting(Server server) throws Exception;

    /**
     * Invoked when a {@link Server} finished its startup procedure successfully and it started to serve
     * incoming requests.
     */
    void serverStarted(Server server) throws Exception;

    /**
     * Invoked when a {@link Server} begins its shutdown procedure.
     */
    void serverStopping(Server server) throws Exception;

    /**
     * Invoked when a {@link Server} finished its shutdown procedure and it stopped to serve incoming requests.
     */
    void serverStopped(Server server) throws Exception;
}
