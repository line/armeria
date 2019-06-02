/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.properties;

import static com.google.common.base.Preconditions.checkState;

import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * A restartable thread utility class.
 */
final class RestartableThread {

    @Nullable
    private Thread thread;
    private final Supplier<Runnable> runnableSupplier;
    private final String name;

    /**
     * Initialize a restartable thread.
     * @param name name of thread
     * @param runnableSupplier supplies the runnable for the thread to use on each restart
     */
    RestartableThread(String name, Supplier<Runnable> runnableSupplier) {
        this.name = name;
        this.runnableSupplier = runnableSupplier;
    }

    /**
     * Starts thread with runnable if not running yet.
     */
    synchronized void start() {
        if (!isRunning()) {
            checkState(thread == null, "trying to start thread without cleanup");
            thread = new Thread(runnableSupplier.get(), name);
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Stops the current running thread if available.
     */
    synchronized void stop() {
        if (isRunning()) {
            checkState(thread != null, "tried to stop null thread");
            boolean interrupted = false;
            thread.interrupt();
            while (thread.isAlive()) {
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }

            thread = null;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Whether thread is currently running.
     * @return whether thread is currently running
     */
    boolean isRunning() {
        return thread != null && thread.isAlive();
    }
}
