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

import io.netty.channel.EventLoop;

abstract class AbstractEventLoopState implements EventLoopState {

    private final List<EventLoop> eventLoops;
    private final DefaultEventLoopScheduler scheduler;

    /**
     * Updated only when {@link #allActiveRequests()} is 0 by {@link #release(EventLoopEntry)}.
     */
    private long lastActivityTimeNanos = System.nanoTime();

    AbstractEventLoopState(List<EventLoop> eventLoops, DefaultEventLoopScheduler scheduler) {
        this.eventLoops = eventLoops;
        this.scheduler = scheduler;
    }

    List<EventLoop> eventLoops() {
        return eventLoops;
    }

    DefaultEventLoopScheduler scheduler() {
        return scheduler;
    }

    @Override
    public long lastActivityTimeNanos() {
        return lastActivityTimeNanos;
    }

    void setLastActivityTimeNanos() {
        lastActivityTimeNanos = System.nanoTime();
    }
}
