/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;

class DefaultContextAwareExecutorService implements ContextAwareExecutorService {

    private final RequestContext context;
    private final ExecutorService executor;

    DefaultContextAwareExecutorService(RequestContext context, ExecutorService executor) {
        this.context = context;
        this.executor = executor;
    }

    @Override
    public final RequestContext context() {
        return context;
    }

    @Override
    public final void shutdown() {
        executor.shutdown();
    }

    @Override
    public final List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    @Override
    public Future<?> submit(Runnable task) {
        return executor.submit(context().makeContextAware(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return executor.submit(context().makeContextAware(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(context().makeContextAware(task));
    }

    @Override
    public final boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public final boolean isTerminated() {
        return executor.isTerminated();
    }

    @Override
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    @Override
    public final <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executor.invokeAll(makeContextAware(tasks));
    }

    @Override
    public final <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return executor.invokeAll(makeContextAware(tasks), timeout, unit);
    }

    @Override
    public final <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return executor.invokeAny(makeContextAware(tasks));
    }

    @Override
    public final <T> T invokeAny(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return executor.invokeAny(makeContextAware(tasks), timeout, unit);
    }

    @Override
    public final void execute(Runnable command) {
        executor.execute(context().makeContextAware(command));
    }

    private <T> Collection<? extends Callable<T>> makeContextAware(
            Collection<? extends Callable<T>> tasks) {
        return tasks.stream().map(context()::makeContextAware).collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("context", context())
                          .add("executor", executor)
                          .toString();
    }

}

