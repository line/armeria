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
/*
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.metric;

import static java.util.Arrays.asList;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import com.linecorp.armeria.common.util.UnstableApi;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.lang.NonNullApi;
import io.micrometer.core.lang.NonNullFields;
import io.micrometer.core.lang.Nullable;

/**
 * Monitors the status of executor service pools. Does not record timings on operations executed in the
 * {@link ExecutorService}, as this requires the instance to be wrapped.
 * Timings are provided separately by wrapping the executor service with {@link TimedExecutorService}.
 *
 * @author Jon Schneider
 * @author Clint Checketts
 * @author Johnny Lim
 */
@NonNullApi
@NonNullFields
@UnstableApi
public class ExecutorServiceMetrics implements MeterBinder {

    // Forked from Micrometer 1.3.6
    // https://github.com/micrometer-metrics/micrometer/blob/e6ff3c2fe9542608a33a62b10fdf1222cd60feae/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/jvm/ExecutorServiceMetrics.java

    @Nullable
    private final ExecutorService executorService;

    private final Iterable<Tag> tags;

    /**
     * Creates a new instance.
     */
    public ExecutorServiceMetrics(@Nullable ExecutorService executorService, String executorServiceName,
                                  Iterable<Tag> tags) {
        this.executorService = executorService;
        this.tags = Tags.concat(tags, "name", executorServiceName);
    }

    /**
     * Record metrics on the use of an {@link Executor}.
     *
     * @param registry     The registry to bind metrics to.
     * @param executor     The executor to instrument.
     * @param executorName Will be used to tag metrics with "name".
     * @param tags         Tags to apply to all recorded metrics.
     * @return The instrumented executor, proxied.
     */
    public static Executor monitor(MeterRegistry registry, Executor executor, String executorName,
                                   Iterable<Tag> tags) {
        if (executor instanceof ExecutorService) {
            return monitor(registry, (ExecutorService) executor, executorName, tags);
        }
        return new TimedExecutor(registry, executor, executorName, tags);
    }

    /**
     * Record metrics on the use of an {@link Executor}.
     *
     * @param registry     The registry to bind metrics to.
     * @param executor     The executor to instrument.
     * @param executorName Will be used to tag metrics with "name".
     * @param tags         Tags to apply to all recorded metrics.
     * @return The instrumented executor, proxied.
     */
    public static Executor monitor(MeterRegistry registry, Executor executor, String executorName,
                                   Tag... tags) {
        return monitor(registry, executor, executorName, asList(tags));
    }

    /**
     * Record metrics on the use of an {@link ExecutorService}.
     *
     * @param registry            The registry to bind metrics to.
     * @param executor            The executor to instrument.
     * @param executorServiceName Will be used to tag metrics with "name".
     * @param tags                Tags to apply to all recorded metrics.
     * @return The instrumented executor, proxied.
     */
    public static ExecutorService monitor(MeterRegistry registry, ExecutorService executor,
                                          String executorServiceName, Iterable<Tag> tags) {
        if (executor instanceof ScheduledExecutorService) {
            return monitor(registry, (ScheduledExecutorService) executor, executorServiceName, tags);
        }
        new ExecutorServiceMetrics(executor, executorServiceName, tags).bindTo(registry);
        return new TimedExecutorService(registry, executor, executorServiceName, tags);
    }

    /**
     * Record metrics on the use of an {@link ExecutorService}.
     *
     * @param registry            The registry to bind metrics to.
     * @param executor            The executor to instrument.
     * @param executorServiceName Will be used to tag metrics with "name".
     * @param tags                Tags to apply to all recorded metrics.
     * @return The instrumented executor, proxied.
     */
    public static ExecutorService monitor(MeterRegistry registry, ExecutorService executor,
                                          String executorServiceName, Tag... tags) {
        return monitor(registry, executor, executorServiceName, asList(tags));
    }

    /**
     * Record metrics on the use of a {@link ScheduledExecutorService}.
     *
     * @param registry            The registry to bind metrics to.
     * @param executor            The scheduled executor to instrument.
     * @param executorServiceName Will be used to tag metrics with "name".
     * @param tags                Tags to apply to all recorded metrics.
     * @return The instrumented scheduled executor, proxied.
     * @since 1.3.0
     */
    public static ScheduledExecutorService monitor(MeterRegistry registry, ScheduledExecutorService executor,
                                                   String executorServiceName, Iterable<Tag> tags) {
        new ExecutorServiceMetrics(executor, executorServiceName, tags).bindTo(registry);
        return new TimedScheduledExecutorService(registry, executor, executorServiceName, tags);
    }

    /**
     * Record metrics on the use of a {@link ScheduledExecutorService}.
     *
     * @param registry            The registry to bind metrics to.
     * @param executor            The scheduled executor to instrument.
     * @param executorServiceName Will be used to tag metrics with "name".
     * @param tags                Tags to apply to all recorded metrics.
     * @return The instrumented scheduled executor, proxied.
     * @since 1.3.0
     */
    public static ScheduledExecutorService monitor(MeterRegistry registry, ScheduledExecutorService executor,
                                                   String executorServiceName, Tag... tags) {
        return monitor(registry, executor, executorServiceName, asList(tags));
    }

    private void monitor(MeterRegistry registry, @Nullable ThreadPoolExecutor tp) {
        if (tp == null) {
            return;
        }

        FunctionCounter.builder("armeria.executor.completed", tp, ThreadPoolExecutor::getCompletedTaskCount)
                       .tags(tags)
                       .description("The approximate total number of tasks that have completed execution")
                       .baseUnit(BaseUnits.TASKS)
                       .register(registry);

        Gauge.builder("armeria.executor.active", tp, ThreadPoolExecutor::getActiveCount)
             .tags(tags)
             .description("The approximate number of threads that are actively executing tasks")
             .baseUnit(BaseUnits.THREADS)
             .register(registry);

        Gauge.builder("armeria.executor.queued", tp, tpRef -> tpRef.getQueue().size())
             .tags(tags)
             .description("The approximate number of tasks that are queued for execution")
             .baseUnit(BaseUnits.TASKS)
             .register(registry);

        Gauge.builder("armeria.executor.queue.remaining", tp, tpRef -> tpRef.getQueue().remainingCapacity())
             .tags(tags)
             .description(
                     "The number of additional elements that this queue can ideally accept without blocking")
             .baseUnit(BaseUnits.TASKS)
             .register(registry);

        Gauge.builder("armeria.executor.pool.size", tp, ThreadPoolExecutor::getPoolSize)
             .tags(tags)
             .description("The current number of threads in the pool")
             .baseUnit(BaseUnits.THREADS)
             .register(registry);
    }

    private void monitor(MeterRegistry registry, ForkJoinPool fj) {
        FunctionCounter.builder("armeria.executor.steals", fj, ForkJoinPool::getStealCount)
                       .tags(tags)
                       .description("Estimate of the total number of tasks stolen from " +
                                    "one thread's work queue by another. The reported value " +
                                    "underestimates the actual total number of steals when the pool " +
                                    "is not quiescent")
                       .register(registry);

        Gauge.builder("armeria.executor.queued", fj, ForkJoinPool::getQueuedTaskCount)
             .tags(tags)
             .description("An estimate of the total number of tasks currently held in queues by worker threads")
             .register(registry);

        Gauge.builder("armeria.executor.active", fj, ForkJoinPool::getActiveThreadCount)
             .tags(tags)
             .description("An estimate of the number of threads that are currently stealing or executing tasks")
             .register(registry);

        Gauge.builder("armeria.executor.running", fj, ForkJoinPool::getRunningThreadCount)
             .tags(tags)
             .description(
                     "An estimate of the number of worker threads that are not blocked waiting to join tasks " +
                     "or for other managed synchronization threads")
             .register(registry);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (executorService == null) {
            return;
        }

        final String className = executorService.getClass().getName();

        if (executorService instanceof ThreadPoolExecutor) {
            monitor(registry, (ThreadPoolExecutor) executorService);
        } else if ("java.util.concurrent.Executors$DelegatedScheduledExecutorService".equals(className)) {
            monitor(registry, unwrapThreadPoolExecutor(executorService, executorService.getClass()));
        } else if ("java.util.concurrent.Executors$FinalizableDelegatedExecutorService".equals(className)) {
            monitor(registry,
                    unwrapThreadPoolExecutor(executorService, executorService.getClass().getSuperclass()));
        } else if (executorService instanceof ForkJoinPool) {
            monitor(registry, (ForkJoinPool) executorService);
        }
    }

    /**
     * Every ScheduledThreadPoolExecutor created by {@link Executors} is wrapped. Also,
     * {@link Executors#newSingleThreadExecutor()} wrap a regular {@link ThreadPoolExecutor}.
     */
    @Nullable
    private ThreadPoolExecutor unwrapThreadPoolExecutor(ExecutorService executor, Class<?> wrapper) {
        try {
            final Field e = wrapper.getDeclaredField("e");
            e.setAccessible(true);
            return (ThreadPoolExecutor) e.get(executor);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Do nothing. We simply can't get to the underlying ThreadPoolExecutor.
        }
        return null;
    }
}
