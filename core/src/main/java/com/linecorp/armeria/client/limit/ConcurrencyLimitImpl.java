package com.linecorp.armeria.client.limit;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

public class ConcurrencyLimitImpl implements ConcurrencyLimit<ClientRequestContext> {

    private final int maxParallelism;
    private final int maxPendingAcquires;
    private final long acquireTimeoutNanos;

    private final Queue<AcquireTask> pendingAcquireQueue;
    private final AtomicInteger acquiredPermitCount = new AtomicInteger();
    private final Runnable timeoutTask;
    private int pendingAcquireCount;
    private final Permit semaphorePermit = () -> decrementAndRunTaskQueue();

    public ConcurrencyLimitImpl(
            long acquireTimeoutMillis,
            int maxParallelism,
            int maxPendingAcquires) {
        this.maxParallelism = maxParallelism;
        this.maxPendingAcquires = maxPendingAcquires;
        this.pendingAcquireQueue = new ArrayDeque<>(maxPendingAcquires);
        this.acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
        this.timeoutTask = new TimeoutTask();
    }

    @Override
    public CompletableFuture<Permit> acquire(ClientRequestContext requestContext) {
        return Futures.toCompletableFuture(acquire(requestContext, requestContext.eventLoop().newPromise()));
    }

    public int acquiredPermitCount() {
        return acquiredPermitCount.get();
    }

    public int availablePermitCount() {
        return maxParallelism - acquiredPermitCount();
    }

    private Future<Permit> acquire(final ClientRequestContext requestContext, final Promise<Permit> promise) {
        EventLoop executor = requestContext.eventLoop().withoutContext();
        try {
            if (executor.inEventLoop()) {
                acquire0(executor, promise);
            } else {
                executor.execute(() -> acquire0(executor, promise));
            }
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise;
    }

    private void acquire0(EventLoop executor, final Promise<Permit> promise) {
        assert executor.inEventLoop();

        if (acquiredPermitCount.get() < maxParallelism) {
            assert acquiredPermitCount.get() >= 0;

            // We need to create a new promise as we need to ensure the AcquireListener runs in the correct
            // EventLoop
            Promise<Permit> p = executor.newPromise();
            AcquireListener l = new AcquireListener(executor, promise);
            l.acquired();
            p.addListener(l);
            newPermit(p);
        } else {
            if (pendingAcquireCount >= maxPendingAcquires) {
                tooManyOutstanding(promise);
            } else {
                ScheduledFuture<?> timeoutFuture = executor.schedule(timeoutTask,
                                                                     acquireTimeoutNanos,
                                                                     TimeUnit.NANOSECONDS);
                AcquireTask task = new AcquireTask(executor, promise, timeoutFuture);
                pendingAcquireQueue.offer(task);
                ++pendingAcquireCount;
            }

            assert pendingAcquireCount > 0;
        }
    }

    private void tooManyOutstanding(Promise<?> promise) {
        promise.setFailure(new IllegalStateException("Too many outstanding acquire operations"));
    }

    private void decrementAndRunTaskQueue() {
        // We should never have a negative value.
        int currentCount = acquiredPermitCount.decrementAndGet();
        assert currentCount >= 0;

        // Run the pending acquire tasks before notify the original promise so if the user would
        // try to acquire again from the ChannelFutureListener and the pendingAcquireCount is >=
        // maxPendingAcquires we may be able to run some pending tasks first and so allow to add
        // more.
        runTaskQueue();
    }

    private void runTaskQueue() {
        while (acquiredPermitCount.get() < maxParallelism) {
            AcquireTask task = pendingAcquireQueue.poll();
            if (task == null) {
                break;
            }

            // Cancel the timeout if one was scheduled
            ScheduledFuture<?> timeoutFuture = task.timeoutFuture;
            timeoutFuture.cancel(false);

            --pendingAcquireCount;
            task.acquired();

            newPermit(task.promise);
        }

        // We should never have a negative value.
        assert pendingAcquireCount >= 0;
        assert acquiredPermitCount.get() >= 0;
    }

    private Future<Permit> newPermit(Promise<Permit> promise) {
        promise.setSuccess(semaphorePermit);
        return promise;
    }

    private final class AcquireTask extends AcquireListener {
        final Promise<Permit> promise;
        final long expireNanoTime = System.nanoTime() + acquireTimeoutNanos;
        final ScheduledFuture<?> timeoutFuture;

        AcquireTask(EventLoop executor, Promise<Permit> promise, ScheduledFuture<?> timeoutFuture) {
            super(executor, promise);
            this.timeoutFuture = timeoutFuture;
            this.promise = executor.<Permit>newPromise().addListener(this);
        }
    }

    private class TimeoutTask implements Runnable {
        @Override
        public final void run() {
            long nanoTime = System.nanoTime();
            for (; ; ) {
                AcquireTask task = pendingAcquireQueue.peek();
                if (task == null || nanoTime - task.expireNanoTime < 0) {
                    break;
                }
                pendingAcquireQueue.remove();

                --pendingAcquireCount;
                task.acquired();
                task.promise.setFailure(UnprocessedRequestException.of(RequestTimeoutException.get()));
            }
        }
    }

    private class AcquireListener implements FutureListener<Permit> {
        protected final EventLoop executor;
        private final Promise<Permit> originalPromise;
        protected boolean acquired;

        AcquireListener(EventLoop executor, Promise<Permit> originalPromise) {
            this.executor = executor;
            this.originalPromise = originalPromise;
        }

        @Override
        public void operationComplete(Future<Permit> future) throws Exception {
            assert executor.inEventLoop();

            if (future.isSuccess()) {
                originalPromise.setSuccess(future.getNow());
            } else {
                if (acquired) {
                    decrementAndRunTaskQueue();
                } else {
                    runTaskQueue();
                }

                originalPromise.setFailure(future.cause());
            }
        }

        public void acquired() {
            if (acquired) {
                return;
            }
            acquiredPermitCount.incrementAndGet();
            acquired = true;
        }
    }
}
