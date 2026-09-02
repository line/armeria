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
package com.linecorp.armeria.client.grpc.endpoint.healthcheck;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

/**
 * Abstract class that provides common structure for {@link GrpcHealthChecker} and
 * {@link GrpcHealthCheckWatcher}.
 */
abstract class AbstractGrpcHealthChecker implements AsyncCloseable {

    static final double HEALTHY = 1d;
    static final double UNHEALTHY = 0d;

    private final ReentrantLock lock = new ReentrantShortLock();
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);
    private boolean closed;

    public void start() {
        check();
    }

    protected abstract void check();

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        lock();
        try {
            closed = true;
            cancelActiveCheck();
        } finally {
            unlock();
        }
        future.complete(null);
    }

    @Override
    public void close() {
        closeable.close();
    }

    /**
     * Cancels the in-flight health check RPC, if any. Invoked while holding the lock.
     */
    protected void cancelActiveCheck() {}

    /**
     * Returns whether this health checker has been closed. Subclasses must check this before
     * issuing a new RPC or scheduling the next check, to avoid running health checks after closure.
     */
    protected boolean isClosed() {
        return closed;
    }

    protected void lock() {
        lock.lock();
    }

    protected void unlock() {
        lock.unlock();
    }

    /**
     * Logs a failed health check RPC at a severity appropriate to the cause: {@code WARN} for an
     * {@link Status.Code#UNIMPLEMENTED} status (indicating the endpoint doesn't implement the gRPC
     * health check service) or an exception that didn't originate from a gRPC status, since both
     * usually indicate a misconfiguration; {@code TRACE} for any other gRPC status exception, since
     * those are expected to occur transiently while health checking.
     */
    protected static void logCheckFailure(Logger logger, Endpoint endpoint, Throwable throwable) {
        if (throwable instanceof StatusRuntimeException || throwable instanceof StatusException) {
            final Status status = Status.fromThrowable(throwable);
            if (status.getCode() == Status.Code.UNIMPLEMENTED) {
                logger.warn("gRPC health checking is not implemented by endpoint {}", endpoint, throwable);
            } else {
                logger.trace("Failed health check on endpoint {}", endpoint, throwable);
            }
        } else {
            logger.warn("Unexpected exception while performing health check on endpoint {}",
                    endpoint, throwable);
        }
    }
}
