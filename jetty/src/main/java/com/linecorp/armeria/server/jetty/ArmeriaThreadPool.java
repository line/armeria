/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.jetty;

import java.util.concurrent.ExecutorService;

import org.eclipse.jetty.util.thread.ThreadPool;

final class ArmeriaThreadPool implements ThreadPool {
    private final ExecutorService blockingTaskExecutor;

    ArmeriaThreadPool(ExecutorService blockingTaskExecutor) {
        this.blockingTaskExecutor = blockingTaskExecutor;
    }

    @Override
    public void join() {}

    @Override
    public int getThreads() {
        return -1;
    }

    @Override
    public int getIdleThreads() {
        return -1;
    }

    @Override
    public boolean isLowOnThreads() {
        return false;
    }

    @Override
    public void execute(Runnable command) {
        blockingTaskExecutor.execute(command);
    }
}
