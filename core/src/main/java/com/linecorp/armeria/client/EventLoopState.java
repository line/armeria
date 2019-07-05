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

import com.google.common.annotations.VisibleForTesting;

import io.netty.channel.EventLoop;

/**
 * Handles acquiring the {@link EventLoopEntry} for an endpoint.
 */
interface EventLoopState {

    static EventLoopState of(List<EventLoop> eventLoops, int maxNumEventLoops,
                             DefaultEventLoopScheduler scheduler) {
        if (maxNumEventLoops == 1) {
            return new OneEventLoopState(eventLoops, scheduler);
        }
        // TODO(minwoox) Introduce array based state which is used when the maxNumEventLoops is greater than 1
        //               and less than N for the performance.
        return new HeapBasedEventLoopState(eventLoops, maxNumEventLoops, scheduler);
    }

    EventLoopEntry acquire();

    void release(EventLoopEntry e);

    @VisibleForTesting
    List<EventLoopEntry> entries();

    int allActiveRequests();

    long lastActivityTimeNanos();
}
