/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;

final class DefaultBlockingTaskExecutor implements BlockingTaskExecutor {

    private final ScheduledExecutorService delegate;
    private final AtomicInteger taskCounter = new AtomicInteger();

    DefaultBlockingTaskExecutor(ScheduledExecutorService delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        requireNonNull(command, "command");
        final AtomicBoolean completedOrCancelled = new AtomicBoolean();
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<?> future = delegate.schedule(() -> {
                try {
                    command.run();
                } finally {
                    if (completedOrCancelled.compareAndSet(false, true)) {
                        taskCounter.decrementAndGet();
                    }
                }
            }, delay, unit);
            submitted = true;
            return new BlockingScheduledFuture<>(future, completedOrCancelled, taskCounter);
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        requireNonNull(callable, "callable");
        final AtomicBoolean completedOrCancelled = new AtomicBoolean();
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<V> future = delegate.schedule(() -> {
                try {
                    return callable.call();
                } finally {
                    if (completedOrCancelled.compareAndSet(false, true)) {
                        taskCounter.decrementAndGet();
                    }
                }
            }, delay, unit);
            submitted = true;
            return new BlockingScheduledFuture<>(future, completedOrCancelled, taskCounter);
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        requireNonNull(command, "command");
        final AtomicBoolean completedOrCancelled = new AtomicBoolean();
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<?> future = delegate.scheduleAtFixedRate(() -> {
                try {
                    command.run();
                } catch (Throwable t) {
                    if (completedOrCancelled.compareAndSet(false, true)) {
                        taskCounter.decrementAndGet();
                    }
                    Exceptions.throwUnsafely(t);
                }
            }, initialDelay, period, unit);
            submitted = true;
            return new BlockingScheduledFuture<>(future, completedOrCancelled, taskCounter);
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        requireNonNull(command, "command");
        final AtomicBoolean completedOrCancelled = new AtomicBoolean();
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<?> future = delegate.scheduleWithFixedDelay(() -> {
                try {
                    command.run();
                } catch (Throwable t) {
                    if (completedOrCancelled.compareAndSet(false, true)) {
                        taskCounter.decrementAndGet();
                    }
                    Exceptions.throwUnsafely(t);
                }
            }, initialDelay, delay, unit);
            submitted = true;
            return new BlockingScheduledFuture<>(future, completedOrCancelled, taskCounter);
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        final List<Runnable> unexecuted = delegate.shutdownNow();
        taskCounter.addAndGet(-unexecuted.size());
        return unexecuted;
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        requireNonNull(task, "task");
        final AtomicBoolean completedOrCancelled = new AtomicBoolean();
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final Future<T> future = delegate.submit(() -> {
                try {
                    return task.call();
                } finally {
                    if (completedOrCancelled.compareAndSet(false, true)) {
                        taskCounter.decrementAndGet();
                    }
                }
            });
            submitted = true;
            return new BlockingFuture<>(future, completedOrCancelled, taskCounter);
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        requireNonNull(task, "task");
        final AtomicBoolean completedOrCancelled = new AtomicBoolean();
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final Future<T> future = delegate.submit(() -> {
                try {
                    task.run();
                } finally {
                    if (completedOrCancelled.compareAndSet(false, true)) {
                        taskCounter.decrementAndGet();
                    }
                }
            }, result);
            submitted = true;
            return new BlockingFuture<>(future, completedOrCancelled, taskCounter);
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public Future<?> submit(Runnable task) {
        requireNonNull(task, "task");
        final AtomicBoolean completedOrCancelled = new AtomicBoolean();
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final Future<?> future = delegate.submit(() -> {
                try {
                    task.run();
                } finally {
                    if (completedOrCancelled.compareAndSet(false, true)) {
                        taskCounter.decrementAndGet();
                    }
                }
            });
            submitted = true;
            return new BlockingFuture<>(future, completedOrCancelled, taskCounter);
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        requireNonNull(tasks, "tasks");
        final int numTasks = tasks.size();
        if (numTasks == 0) {
            return delegate.invokeAll(tasks);
        }
        final AtomicInteger remaining = new AtomicInteger(numTasks);
        taskCounter.addAndGet(numTasks);
        boolean submitted = false;
        try {
            final List<Callable<T>> wrapped = tasks.stream().map(task -> {
                requireNonNull(task, "task");
                return (Callable<T>) () -> {
                    try {
                        return task.call();
                    } finally {
                        remaining.decrementAndGet();
                        taskCounter.decrementAndGet();
                    }
                };
            }).collect(ImmutableList.toImmutableList());
            final List<Future<T>> futures = delegate.invokeAll(wrapped);
            submitted = true;
            return futures;
        } finally {
            if (!submitted) {
                taskCounter.addAndGet(-remaining.get());
            } else {
                final int left = remaining.getAndSet(0);
                if (left > 0) {
                    taskCounter.addAndGet(-left);
                }
            }
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
                                         TimeUnit unit) throws InterruptedException {
        requireNonNull(tasks, "tasks");
        final int numTasks = tasks.size();
        if (numTasks == 0) {
            return delegate.invokeAll(tasks, timeout, unit);
        }
        final AtomicInteger remaining = new AtomicInteger(numTasks);
        taskCounter.addAndGet(numTasks);
        boolean submitted = false;
        try {
            final List<Callable<T>> wrapped = tasks.stream().map(task -> {
                requireNonNull(task, "task");
                return (Callable<T>) () -> {
                    try {
                        return task.call();
                    } finally {
                        remaining.decrementAndGet();
                        taskCounter.decrementAndGet();
                    }
                };
            }).collect(ImmutableList.toImmutableList());
            final List<Future<T>> futures = delegate.invokeAll(wrapped, timeout, unit);
            submitted = true;
            return futures;
        } finally {
            if (!submitted) {
                taskCounter.addAndGet(-remaining.get());
            } else {
                final int left = remaining.getAndSet(0);
                if (left > 0) {
                    taskCounter.addAndGet(-left);
                }
            }
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        requireNonNull(tasks, "tasks");
        final int numTasks = tasks.size();
        if (numTasks == 0) {
            return delegate.invokeAny(tasks);
        }
        final AtomicInteger remaining = new AtomicInteger(numTasks);
        taskCounter.addAndGet(numTasks);
        boolean submitted = false;
        try {
            final List<Callable<T>> wrapped = tasks.stream().map(task -> {
                requireNonNull(task, "task");
                return (Callable<T>) () -> {
                    try {
                        return task.call();
                    } finally {
                        remaining.decrementAndGet();
                        taskCounter.decrementAndGet();
                    }
                };
            }).collect(ImmutableList.toImmutableList());
            final T result = delegate.invokeAny(wrapped);
            submitted = true;
            return result;
        } finally {
            if (!submitted) {
                taskCounter.addAndGet(-remaining.get());
            } else {
                final int left = remaining.getAndSet(0);
                if (left > 0) {
                    taskCounter.addAndGet(-left);
                }
            }
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        requireNonNull(tasks, "tasks");
        final int numTasks = tasks.size();
        if (numTasks == 0) {
            return delegate.invokeAny(tasks, timeout, unit);
        }
        final AtomicInteger remaining = new AtomicInteger(numTasks);
        taskCounter.addAndGet(numTasks);
        boolean submitted = false;
        try {
            final List<Callable<T>> wrapped = tasks.stream().map(task -> {
                requireNonNull(task, "task");
                return (Callable<T>) () -> {
                    try {
                        return task.call();
                    } finally {
                        remaining.decrementAndGet();
                        taskCounter.decrementAndGet();
                    }
                };
            }).collect(ImmutableList.toImmutableList());
            final T result = delegate.invokeAny(wrapped, timeout, unit);
            submitted = true;
            return result;
        } finally {
            if (!submitted) {
                taskCounter.addAndGet(-remaining.get());
            } else {
                final int left = remaining.getAndSet(0);
                if (left > 0) {
                    taskCounter.addAndGet(-left);
                }
            }
        }
    }

    @Override
    public void execute(Runnable command) {
        requireNonNull(command, "command");
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    taskCounter.decrementAndGet();
                }
            });
            submitted = true;
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public ScheduledExecutorService unwrap() {
        return delegate;
    }

    @Override
    public int numPendingTasks() {
        return Math.max(0, taskCounter.get());
    }

    private static class BlockingFuture<T> implements Future<T> {

        private final Future<T> delegate;
        private final AtomicBoolean completedOrCancelled;
        private final AtomicInteger taskCounter;

        BlockingFuture(Future<T> delegate, AtomicBoolean completedOrCancelled,
                       AtomicInteger taskCounter) {
            this.delegate = delegate;
            this.completedOrCancelled = completedOrCancelled;
            this.taskCounter = taskCounter;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            final boolean cancelled = delegate.cancel(mayInterruptIfRunning);
            if (cancelled && completedOrCancelled.compareAndSet(false, true)) {
                taskCounter.decrementAndGet();
            }
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    private static final class BlockingScheduledFuture<V> extends BlockingFuture<V>
            implements ScheduledFuture<V> {

        private final ScheduledFuture<V> delegate;

        BlockingScheduledFuture(ScheduledFuture<V> delegate, AtomicBoolean completedOrCancelled,
                                AtomicInteger taskCounter) {
            super(delegate, completedOrCancelled, taskCounter);
            this.delegate = delegate;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return delegate.getDelay(unit);
        }

        @Override
        public int compareTo(Delayed o) {
            return delegate.compareTo(o);
        }
    }
}
