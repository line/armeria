/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.client.grpc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;

@UnstableApi
public final class DeferredClientCall<I, O> extends ClientCall<I, O> {

    private static final List<Runnable> NOOP_TASKS = ImmutableList.of();

    private List<Runnable> pendingTasks = new ArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();

    private final Executor executor;

    @Nullable
    private DeferredListner<O> deferredListner;

    @Nullable
    private volatile ArmeriaClientCall<I, O> delegate;

    public DeferredClientCall(CompletableFuture<ClientCall<I, O>> clientFuture) {
        executor = MoreExecutors.newSequentialExecutor(CommonPools.blockingTaskExecutor());
        clientFuture.handleAsync((delegate, cause) -> {
            if (cause != null) {
                // TODO
                return null;
            }
            lock.lock();
            try {
                this.delegate = (ArmeriaClientCall<I, O>) delegate;
            } finally {
                lock.unlock();
            }
            drainPendingTasks();
            return null;
        }, executor);
    }

    @Override
    public void start(Listener<O> responseListener, Metadata headers) {
        lock.lock();
        try {
            if (delegate != null) {
                delegate.start(responseListener, headers);
            } else {
                deferredListner = new DeferredListner<>(responseListener);
                pendingTasks.add(() -> delegate.start(deferredListner, headers));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void request(int numMessages) {
        lock.lock();
        try {
            if (delegate != null) {
                delegate.request(numMessages);
            } else {
                pendingTasks.add(() -> delegate.request(numMessages));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
        lock.lock();
        try {
            if (delegate != null) {
                delegate.cancel(message, cause);
            } else {
                pendingTasks.add(() -> delegate.cancel(message, cause));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void halfClose() {
        lock.lock();
        try {
            if (delegate != null) {
                delegate.halfClose();
            } else {
                pendingTasks.add(this::halfClose);
            }
        }finally {
            lock.unlock();
        }
    }

    @Override
    public void sendMessage(I message) {
        lock.lock();
        try {
            if (delegate != null) {
                delegate.sendMessage(message);
            } else {
                pendingTasks.add(() -> delegate.sendMessage(message));
            }
        } finally {
            lock.unlock();
        }
    }

    private void drainPendingTasks() {
        List<Runnable> runnables = new ArrayList<>();
        DeferredListner<O> deferredListner;
        while (true) {
            lock.lock();
            try {
                if (pendingTasks.isEmpty()) {
                    pendingTasks = NOOP_TASKS;
                    deferredListner = this.deferredListner;
                    break;
                }
            } finally {
                lock.unlock();
            }
            final List<Runnable> tmp = runnables;
            runnables = pendingTasks;
            pendingTasks = tmp;
            for (Runnable runnable : runnables) {
                runnable.run();
            }
            runnables.clear();
        }
        if (deferredListner != null) {
            executor.execute(delegate.ctx().makeContextAware(deferredListner::drainPendingTasks));
        }
    }

    class DeferredListner<O> extends Listener<O> {
        private final Listener<O> listener;
        private final ReentrantLock lock = new ReentrantLock();
        private List<Runnable> pendingTasks = new ArrayList<>();
        private volatile boolean passThrough;

        DeferredListner(Listener<O> listener) {
            this.listener = listener;
        }

        private void deferredOrExecute(Runnable runnable) {
            lock.lock();
            try {
                if (!passThrough) {
                    pendingTasks.add(runnable);
                    return;
                }
            } finally {
                lock.unlock();
            }
            runnable.run();
        }

        @Override
        public void onHeaders(Metadata headers) {
            if (passThrough) {
                listener.onHeaders(headers);
            } else {
                deferredOrExecute(() -> listener.onHeaders(headers));
            }
        }

        @Override
        public void onMessage(O message) {
            if (passThrough) {
                listener.onMessage(message);
            } else {
                deferredOrExecute(() -> listener.onMessage(message));
            }
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            deferredOrExecute(() -> listener.onClose(status, trailers));
        }

        @Override
        public void onReady() {
            if (passThrough) {
                listener.onReady();
            } else {
                deferredOrExecute(listener::onReady);
            }
        }

        void drainPendingTasks() {
            List<Runnable> runnables = new ArrayList<>();
            while (true) {
                lock.lock();
                try {
                    if (pendingTasks.isEmpty()) {
                        passThrough = true;
                        break;
                    }
                    final List<Runnable> tmp = runnables;
                    runnables = pendingTasks;
                    pendingTasks = tmp;
                    for (Runnable runnable : runnables) {
                        runnable.run();
                    }
                    runnables.clear();
                } finally {
                    lock.unlock();
                }
            }
        }
    }
}
