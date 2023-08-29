/*
 *  Copyright 2023 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.Iterators;

import com.linecorp.armeria.internal.common.metric.MicrometerUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link MeterBinder} to observe Netty {@link EventLoopGroup}s. The following stats are currently
 * exported per registered {@link MeterIdPrefix}.
 *
 * <ul>
 *   <li>"event.loop.workers" (gauge) - the total number of Netty's event loops</li>
 *   <li>"event.loop.pending.tasks" (gauge)
 *     - the total number of IO tasks waiting to be run on event loops</li>
 * </ul>
 **/
final class EventLoopMetrics implements MeterBinder {

    private final EventLoopGroup eventLoopGroup;
    private final MeterIdPrefix idPrefix;

    /**
     * Creates an instance of {@link EventLoopMetrics}.
     */
    EventLoopMetrics(EventLoopGroup eventLoopGroup, MeterIdPrefix idPrefix) {
        this.eventLoopGroup = requireNonNull(eventLoopGroup, "eventLoopGroup");
        this.idPrefix = idPrefix;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        final Self metrics = MicrometerUtil.register(registry, idPrefix, Self.class, Self::new);
        metrics.add(eventLoopGroup);
    }

    /**
     * An actual implementation of {@link EventLoopMetrics}.
     */
    static final class Self {
        private final Set<EventLoopGroup> registry = ConcurrentHashMap.newKeySet(2);

        Self(MeterRegistry parent, MeterIdPrefix idPrefix) {

            final String numWorkers = idPrefix.name("event.loop.workers");
            parent.gauge(numWorkers, idPrefix.tags(), this, Self::numWorkers);

            final String pendingTasks = idPrefix.name("event.loop.pending.tasks");
            parent.gauge(pendingTasks, idPrefix.tags(), this, Self::pendingTasks);
        }

        void add(EventLoopGroup eventLoopGroup) {
            registry.add(eventLoopGroup);
        }

        double numWorkers() {
            int result = 0;
            for (EventLoopGroup group : registry) {
                // Purge event loop groups that were shutdown.
                if (group.isShutdown()) {
                    registry.remove(group);
                    continue;
                }
                result += Iterators.size(group.iterator());
            }
            return result;
        }

        double pendingTasks() {
            int result = 0;
            for (EventLoopGroup group : registry) {
                // Purge event loop groups that were shutdown.
                if (group.isShutdown()) {
                    registry.remove(group);
                    continue;
                }
                for (EventExecutor eventLoop : group) {
                    if (eventLoop instanceof SingleThreadEventLoop) {
                        result += ((SingleThreadEventLoop) eventLoop).pendingTasks();
                    }
                }
            }
            return result;
        }
    }
}
