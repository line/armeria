/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.netty.channel.EventLoop;

abstract class AbstractEventLoopState {

    static AbstractEventLoopState of(List<EventLoop> eventLoops, int maxNumEventLoops,
                                     DefaultEventLoopScheduler scheduler) {
        if (maxNumEventLoops == 1) {
            return new OneEventLoopState(eventLoops, scheduler);
        } else if (maxNumEventLoops <= 128) {
            return new ArrayBasedEventLoopState(eventLoops, maxNumEventLoops, scheduler);
        }
        return new HeapBasedEventLoopState(eventLoops, maxNumEventLoops, scheduler);
    }

    private final ReentrantLock lock = new ReentrantShortLock();
    private final List<EventLoop> eventLoops;
    private final DefaultEventLoopScheduler scheduler;

    /**
     * Updated only when {@link #allActiveRequests()} is 0 by {@link #release(AbstractEventLoopEntry)}.
     */
    private long lastActivityTimeNanos = System.nanoTime();

    AbstractEventLoopState(List<EventLoop> eventLoops, DefaultEventLoopScheduler scheduler) {
        this.eventLoops = eventLoops;
        this.scheduler = scheduler;
    }

    final List<EventLoop> eventLoops() {
        return eventLoops;
    }

    final DefaultEventLoopScheduler scheduler() {
        return scheduler;
    }

    final long lastActivityTimeNanos() {
        return lastActivityTimeNanos;
    }

    final void setLastActivityTimeNanos() {
        lastActivityTimeNanos = System.nanoTime();
    }

    protected final void lock() {
        lock.lock();
    }

    protected final void unlock() {
        lock.unlock();
    }

    abstract AbstractEventLoopEntry acquire();

    abstract void release(AbstractEventLoopEntry e);

    @VisibleForTesting
    abstract AbstractEventLoopEntry[] entries();

    abstract int allActiveRequests();
}
