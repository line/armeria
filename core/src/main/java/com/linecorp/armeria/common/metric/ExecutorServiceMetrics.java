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
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.UnstableApi;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Monitors the status of executor service pools. Does not record timings on operations executed in the
 * {@link ExecutorService}, as this requires the instance to be wrapped.
 * Timings are provided separately by wrapping the executor service with {@link TimedExecutorService}.
 *
 * @author Jon Schneider
 * @author Clint Checketts
 * @author Johnny Lim
 */
@UnstableApi
public final class ExecutorServiceMetrics implements MeterBinder {

    // Forked from Micrometer 1.3.6
    // https://github.com/micrometer-metrics/micrometer/blob/e6ff3c2fe9542608a33a62b10fdf1222cd60feae/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/jvm/ExecutorServiceMetrics.java

    /**
     * Record metrics on the use of an {@link Executor}.
     *
     * @param registry the registry to bind metrics to.
     * @param executor the executor to instrument.
     * @param executorName will be used to tag metrics with "name".
     * @param metricPrefix the metricPrefix of the metric name.
     * @param tags tags to apply to all recorded metrics.
     * @return the instrumented executor, proxied.
     */
    public static Executor monitor(MeterRegistry registry, Executor executor, String executorName,
                                   String metricPrefix, Iterable<Tag> tags) {
        if (executor instanceof ExecutorService) {
            return monitor(registry, (ExecutorService) executor, executorName, metricPrefix, tags);
        }

        requireNonNull(registry, "registry");
        requireNonNull(executor, "executor");
        requireNonNull(executorName, "executorName");
        requireNonNull(metricPrefix, "metricPrefix");
        requireNonNull(tags, "tags");
        return new TimedExecutor(registry, executor, executorName, metricPrefix, tags);
    }

    /**
     * Record metrics on the use of an {@link Executor}.
     *
     * @param registry the registry to bind metrics to.
     * @param executor the executor to instrument.
     * @param executorName will be used to tag metrics with "name".
     * @param metricPrefix the prefix of the metric name.
     * @param tags tags to apply to all recorded metrics.
     * @return the instrumented executor, proxied.
     */
    public static Executor monitor(MeterRegistry registry, Executor executor, String executorName,
                                   String metricPrefix, Tag... tags) {
        requireNonNull(tags, "tags");
        return monitor(registry, executor, executorName, metricPrefix, asList(tags));
    }

    /**
     * Record metrics on the use of an {@link ExecutorService}.
     *
     * @param registry the registry to bind metrics to.
     * @param executor the executor to instrument.
     * @param executorServiceName Will be used to tag metrics with "name".
     * @param metricPrefix the prefix of the metric name.
     * @param tags tags to apply to all recorded metrics.
     * @return the instrumented executor, proxied.
     */
    public static ExecutorService monitor(MeterRegistry registry, ExecutorService executor,
                                          String executorServiceName, String metricPrefix, Iterable<Tag> tags) {
        if (executor instanceof ScheduledExecutorService) {
            return monitor(registry, (ScheduledExecutorService) executor,
                           executorServiceName, metricPrefix, tags);
        }

        requireNonNull(registry, "registry");
        requireNonNull(executor, "executor");
        requireNonNull(executorServiceName, "executorServiceName");
        requireNonNull(metricPrefix, "metricPrefix");
        requireNonNull(tags, "tags");

        new ExecutorServiceMetrics(executor, executorServiceName, metricPrefix, tags).bindTo(registry);
        return new TimedExecutorService(registry, executor, executorServiceName, metricPrefix, tags);
    }

    /**
     * Record metrics on the use of an {@link ExecutorService}.
     *
     * @param registry the registry to bind metrics to.
     * @param executor the executor to instrument.
     * @param executorServiceName will be used to tag metrics with "name".
     * @param metricPrefix the prefix of the metric name.
     * @param tags tags to apply to all recorded metrics.
     * @return the instrumented executor, proxied.
     */
    public static ExecutorService monitor(MeterRegistry registry, ExecutorService executor,
                                          String executorServiceName, String metricPrefix, Tag... tags) {
        requireNonNull(tags, "tags");
        return monitor(registry, executor, executorServiceName, metricPrefix, asList(tags));
    }

    /**
     * Record metrics on the use of a {@link ScheduledExecutorService}.
     *
     * @param registry the registry to bind metrics to.
     * @param executor the scheduled executor to instrument.
     * @param executorServiceName Will be used to tag metrics with "name".
     * @param metricPrefix the prefix of the metric name.
     * @param tags tags to apply to all recorded metrics.
     * @return the instrumented scheduled executor, proxied.
     * @since 1.3.0
     */
    public static ScheduledExecutorService monitor(MeterRegistry registry, ScheduledExecutorService executor,
                                                   String executorServiceName, String metricPrefix,
                                                   Iterable<Tag> tags) {
        requireNonNull(registry, "registry");
        requireNonNull(executor, "executor");
        requireNonNull(executorServiceName, "executorServiceName");
        requireNonNull(metricPrefix, "metricPrefix");
        requireNonNull(tags, "tags");

        new ExecutorServiceMetrics(executor, executorServiceName, metricPrefix, tags).bindTo(registry);
        return new TimedScheduledExecutorService(registry, executor, executorServiceName, metricPrefix, tags);
    }

    /**
     * Record metrics on the use of a {@link ScheduledExecutorService}.
     *
     * @param registry the registry to bind metrics to.
     * @param executor the scheduled executor to instrument.
     * @param executorServiceName will be used to tag metrics with "name".
     * @param metricPrefix the prefix of the metric name.
     * @param tags tags to apply to all recorded metrics.
     * @return the instrumented scheduled executor, proxied.
     * @since 1.3.0
     */
    public static ScheduledExecutorService monitor(MeterRegistry registry, ScheduledExecutorService executor,
                                                   String executorServiceName, String metricPrefix,
                                                   Tag... tags) {
        requireNonNull(tags, "tags");
        return monitor(registry, executor, executorServiceName, metricPrefix, asList(tags));
    }

    /**
     * Returns whether the specified {@link Executor} is monitored by {@link MeterRegistry}.
     */
    public static boolean isMonitoredExecutor(Executor executor) {
        return executor instanceof TimedScheduledExecutorService ||
               executor instanceof TimedExecutorService ||
               executor instanceof TimedExecutor ||
               executor instanceof io.micrometer.core.instrument.internal.TimedScheduledExecutorService ||
               executor instanceof io.micrometer.core.instrument.internal.TimedExecutorService ||
               executor instanceof io.micrometer.core.instrument.internal.TimedExecutor;
    }

    /**
     * Every ScheduledThreadPoolExecutor created by {@link Executors} is wrapped. Also,
     * {@link Executors#newSingleThreadExecutor()} wrap a regular {@link ThreadPoolExecutor}.
     */
    @Nullable
    private static ThreadPoolExecutor unwrapThreadPoolExecutor(ExecutorService executor, Class<?> wrapper) {
        try {
            final Field e = wrapper.getDeclaredField("e");
            e.setAccessible(true);
            return (ThreadPoolExecutor) e.get(executor);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Do nothing. We simply can't get to the underlying ThreadPoolExecutor.
        }
        return null;
    }

    @Nullable
    private final ExecutorService executorService;
    private final String metricPrefix;
    private final Iterable<Tag> tags;

    private ExecutorServiceMetrics(@Nullable ExecutorService executorService, String executorServiceName,
                                   String metricPrefix, Iterable<Tag> tags) {
        this.executorService = executorService;
        this.metricPrefix = metricPrefix;
        this.tags = Tags.concat(tags, "name", executorServiceName);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        requireNonNull(registry, "registry");
        if (executorService == null) {
            return;
        }

        final String className = executorService.getClass().getName();

        if (executorService instanceof ThreadPoolExecutor) {
            monitor0(registry, (ThreadPoolExecutor) executorService);
        } else if ("java.util.concurrent.Executors$DelegatedScheduledExecutorService".equals(className)) {
            monitor0(registry, unwrapThreadPoolExecutor(executorService, executorService.getClass()));
        } else if ("java.util.concurrent.Executors$FinalizableDelegatedExecutorService".equals(className)) {
            monitor0(registry,
                     unwrapThreadPoolExecutor(executorService, executorService.getClass().getSuperclass()));
        } else if (executorService instanceof ForkJoinPool) {
            monitor0(registry, (ForkJoinPool) executorService);
        }
    }

    private void monitor0(MeterRegistry registry, @Nullable ThreadPoolExecutor tp) {
        if (tp == null) {
            return;
        }

        FunctionCounter.builder(metricPrefix + ".completed", tp, ThreadPoolExecutor::getCompletedTaskCount)
                       .tags(tags)
                       .description("The approximate total number of tasks that have completed execution")
                       .baseUnit(BaseUnits.TASKS)
                       .register(registry);

        Gauge.builder(metricPrefix + ".active", tp, ThreadPoolExecutor::getActiveCount)
             .tags(tags)
             .description("The approximate number of threads that are actively executing tasks")
             .baseUnit(BaseUnits.THREADS)
             .register(registry);

        Gauge.builder(metricPrefix + ".queued", tp, tpRef -> tpRef.getQueue().size())
             .tags(tags)
             .description("The approximate number of tasks that are queued for execution")
             .baseUnit(BaseUnits.TASKS)
             .register(registry);

        Gauge.builder(metricPrefix + ".queue.remaining", tp, tpRef -> tpRef.getQueue().remainingCapacity())
             .tags(tags)
             .description(
                     "The number of additional elements that this queue can ideally accept without blocking")
             .baseUnit(BaseUnits.TASKS)
             .register(registry);

        Gauge.builder(metricPrefix + ".pool.size", tp, ThreadPoolExecutor::getPoolSize)
             .tags(tags)
             .description("The current number of threads in the pool")
             .baseUnit(BaseUnits.THREADS)
             .register(registry);
    }

    private void monitor0(MeterRegistry registry, ForkJoinPool fj) {
        FunctionCounter.builder(metricPrefix + ".steals", fj, ForkJoinPool::getStealCount)
                       .tags(tags)
                       .description("Estimate of the total number of tasks stolen from " +
                                    "one thread's work queue by another. The reported value " +
                                    "underestimates the actual total number of steals when the pool " +
                                    "is not quiescent")
                       .register(registry);

        Gauge.builder(metricPrefix + ".queued", fj, ForkJoinPool::getQueuedTaskCount)
             .tags(tags)
             .description("An estimate of the total number of tasks currently held in queues by worker threads")
             .register(registry);

        Gauge.builder(metricPrefix + ".active", fj, ForkJoinPool::getActiveThreadCount)
             .tags(tags)
             .description("An estimate of the number of threads that are currently stealing or executing tasks")
             .register(registry);

        Gauge.builder(metricPrefix + ".running", fj, ForkJoinPool::getRunningThreadCount)
             .tags(tags)
             .description(
                     "An estimate of the number of worker threads that are not blocked waiting to join tasks " +
                     "or for other managed synchronization threads")
             .register(registry);
    }
}
