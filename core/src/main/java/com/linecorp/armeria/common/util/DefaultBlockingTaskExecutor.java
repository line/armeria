/*
 * Copyright 2021 LINE Corporation
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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;

final class DefaultBlockingTaskExecutor implements BlockingTaskExecutor {

    private final ScheduledExecutorService delegate;

    private final AtomicInteger taskCounter = new AtomicInteger(0);

    DefaultBlockingTaskExecutor(ScheduledExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<?> future = delegate.schedule(() -> {
                try {
                    command.run();
                } finally {
                    taskCounter.decrementAndGet();
                }
            }, delay, unit);
            submitted = true;
            return future;
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<V> future = delegate.schedule(() -> {
                try {
                    return callable.call();
                } finally {
                    taskCounter.decrementAndGet();
                }
            }, delay, unit);
            submitted = true;
            return future;
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<?> future = delegate.scheduleAtFixedRate(() -> {
                try {
                    command.run();
                } finally {
                    taskCounter.decrementAndGet();
                }
            }, initialDelay, period, unit);
            submitted = true;
            return future;
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final ScheduledFuture<?> future = delegate.scheduleWithFixedDelay(() -> {
                try {
                    command.run();
                } finally {
                    taskCounter.decrementAndGet();
                }
            }, initialDelay, delay, unit);
            submitted = true;
            return future;
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
        return delegate.shutdownNow();
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
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final Future<T> future = delegate.submit(() -> {
                try {
                    return task.call();
                } finally {
                    taskCounter.decrementAndGet();
                }
            });
            submitted = true;
            return future;
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final Future<T> future = delegate.submit(() -> {
                try {
                    task.run();
                } finally {
                    taskCounter.decrementAndGet();
                }
            }, result);
            submitted = true;
            return future;
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public Future<?> submit(Runnable task) {
        boolean submitted = false;
        taskCounter.incrementAndGet();
        try {
            final Future<?> future = delegate.submit(() -> {
                try {
                    task.run();
                } finally {
                    taskCounter.decrementAndGet();
                }
            });
            submitted = true;
            return future;
        } finally {
            if (!submitted) {
                taskCounter.decrementAndGet();
            }
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        final Set<Callable<T>> remains = new HashSet<>(tasks);
        taskCounter.addAndGet(remains.size());
        final List<Future<T>> result = delegate.invokeAll(tasks.stream().map(task -> (Callable<T>) () -> {
            try {
                return task.call();
            } finally {
                remains.remove(task);
                taskCounter.decrementAndGet();
            }
        }).collect(ImmutableList.toImmutableList()));
        taskCounter.addAndGet(-remains.size());
        return result;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
                                         TimeUnit unit) throws InterruptedException {
        final Set<Callable<T>> remains = new HashSet<>(tasks);
        taskCounter.addAndGet(remains.size());
        final List<Future<T>> result = delegate.invokeAll(tasks.stream().map(task -> (Callable<T>) () -> {
            try {
                return task.call();
            } finally {
                remains.remove(task);
                taskCounter.decrementAndGet();
            }
        }).collect(ImmutableList.toImmutableList()), timeout, unit);
        taskCounter.addAndGet(-remains.size());
        return result;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        final Set<Callable<T>> remains = new HashSet<>(tasks);
        taskCounter.addAndGet(remains.size());
        final T result = delegate.invokeAny(tasks.stream().map(task -> (Callable<T>) () -> {
            try {
                return task.call();
            } finally {
                remains.remove(task);
                taskCounter.decrementAndGet();
            }
        }).collect(ImmutableList.toImmutableList()));
        taskCounter.addAndGet(-remains.size());
        return result;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        final Set<Callable<T>> remains = new HashSet<>(tasks);
        taskCounter.addAndGet(remains.size());
        final T result = delegate.invokeAny(tasks.stream().map(task -> (Callable<T>) () -> {
            try {
                return task.call();
            } finally {
                remains.remove(task);
                taskCounter.decrementAndGet();
            }
        }).collect(ImmutableList.toImmutableList()), timeout, unit);
        taskCounter.addAndGet(-remains.size());
        return result;
    }

    @Override
    public void execute(Runnable command) {
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
        delegate.execute(command);
    }

    @Override
    public ScheduledExecutorService unwrap() {
        return delegate;
    }

    @Override
    public int numPendingTasks() {
        return taskCounter.get();
    }
}
