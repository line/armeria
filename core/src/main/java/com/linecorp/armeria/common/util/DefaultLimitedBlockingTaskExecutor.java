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

package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;

final class DefaultLimitedBlockingTaskExecutor implements LimitedBlockingTaskExecutor {
    private final BlockingTaskExecutor delegate;

    private final SettableIntSupplier limitSupplier;

    private final AtomicInteger taskCounter = new AtomicInteger(0);

    DefaultLimitedBlockingTaskExecutor(BlockingTaskExecutor delegate, SettableIntSupplier limitSupplier) {
        this.delegate = delegate;
        this.limitSupplier = limitSupplier;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate.schedule(() -> {
            try {
                taskCounter.incrementAndGet();
                command.run();
            } finally {
                taskCounter.decrementAndGet();
            }
        }, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return delegate.schedule(() -> {
            try {
                taskCounter.incrementAndGet();
                return callable.call();
            } finally {
                taskCounter.decrementAndGet();
            }
        }, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        return delegate.scheduleAtFixedRate(() -> {
            try {
                taskCounter.incrementAndGet();
                command.run();
            } finally {
                taskCounter.decrementAndGet();
            }
        }, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        return delegate.scheduleAtFixedRate(() -> {
            try {
                taskCounter.incrementAndGet();
                command.run();
            } finally {
                taskCounter.decrementAndGet();
            }
        }, initialDelay, delay, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return delegate.isShutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(() -> {
            try {
                taskCounter.incrementAndGet();
                return task.call();
            } finally {
                taskCounter.decrementAndGet();
            }
        });
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(() -> {
            try {
                taskCounter.incrementAndGet();
                task.run();
            } finally {
                taskCounter.decrementAndGet();
            }
        }, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(() -> {
            try {
                taskCounter.incrementAndGet();
                System.out.println(
                        "incremented count " + taskCounter.get() + " by " + Thread.currentThread().getName());
                task.run();
            } finally {
                taskCounter.decrementAndGet();
                System.out.println(
                        "decremented count " + taskCounter.get() + " by " + Thread.currentThread().getName());
            }
        });
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks.stream()
                                       .map(task -> (Callable<T>) () -> {
                                           try {
                                               taskCounter.incrementAndGet();
                                               return task.call();
                                           } finally {
                                               taskCounter.decrementAndGet();
                                           }
                                       })
                                       .collect(ImmutableList.toImmutableList()));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(tasks.stream()
                                       .map(task -> (Callable<T>) () -> {
                                           try {
                                               taskCounter.incrementAndGet();
                                               return task.call();
                                           } finally {
                                               taskCounter.decrementAndGet();
                                           }
                                       })
                                       .collect(ImmutableList.toImmutableList()),
                                  timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks.stream()
                                       .map(task -> (Callable<T>) () -> {
                                           try {
                                               taskCounter.incrementAndGet();
                                               return task.call();
                                           } finally {
                                               taskCounter.decrementAndGet();
                                           }
                                       })
                                       .collect(ImmutableList.toImmutableList()));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks.stream()
                                       .map(task -> (Callable<T>) () -> {
                                           try {
                                               taskCounter.incrementAndGet();
                                               return task.call();
                                           } finally {
                                               taskCounter.decrementAndGet();
                                           }
                                       })
                                       .collect(ImmutableList.toImmutableList()),
                                  timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(() -> {
            try {
                taskCounter.incrementAndGet();
                command.run();
            } finally {
                taskCounter.decrementAndGet();
            }
        });
    }

    @Override
    public ScheduledExecutorService unwrap() {
        return delegate;
    }

    @Override
    public boolean hitLimit() {
        checkArgument(limitSupplier.getAsInt() > 0, "limit must larger than zero");
        return taskCounter.get() >= limitSupplier.getAsInt();
    }
}
