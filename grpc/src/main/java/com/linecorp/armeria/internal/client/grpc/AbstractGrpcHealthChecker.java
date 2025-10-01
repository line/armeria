/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.client.grpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * Abstract class that provides common structure for {@link GrpcHealthChecker} and
 * {@link GrpcHealthCheckWatcher}.
 */
abstract class AbstractGrpcHealthChecker implements AsyncCloseable {

    static final double HEALTHY = 1d;
    static final double UNHEALTHY = 0d;

    private final ReentrantLock lock = new ReentrantShortLock();
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    public void start() {
        check();
    }

    protected abstract void check();

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private synchronized void closeAsync(CompletableFuture<?> future) {
        future.complete(null);
    }

    @Override
    public void close() {
        closeable.close();
    }

    protected void lock() {
        lock.lock();
    }

    protected void unlock() {
        lock.unlock();
    }
}
