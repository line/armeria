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
import java.util.concurrent.locks.ReentrantLock;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.ClientCall;
import io.grpc.Metadata;

@UnstableApi
public final class DeferredClientCall<I, O> extends ClientCall<I, O> {

    private List<Runnable> pendingTasks = new ArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();

    @Nullable
    private volatile ClientCall<I, O> delegate;

    public DeferredClientCall(CompletableFuture<ClientCall<I, O>> clientFuture) {
        clientFuture.handle((delegate, cause) -> {
            if (cause != null) {
                // TODO
                return null;
            }
            lock.lock();
            try {
                this.delegate = delegate;
            } finally {
                lock.unlock();
            }
            drainPendingTasks();
            return null;
        });
    }

    @Override
    public void start(Listener<O> responseListener, Metadata headers) {
        lock.lock();
        try {
            if (delegate != null) {
                delegate.start(responseListener, headers);
            } else {
                pendingTasks.add(() -> delegate.start(responseListener, headers));
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
        while (true) {
            lock.lock();
            try {
                if (pendingTasks.isEmpty()) {
                    break;
                }
            } finally {
                lock.unlock();
            }
            lock.lock();
            try {
                final List<Runnable> tmp = runnables;
                runnables = pendingTasks;
                pendingTasks = tmp;
            } finally {
                lock.unlock();
            }
            for (Runnable runnable : runnables) {
                runnable.run();
            }
            runnables.clear();
        }
    }
}
