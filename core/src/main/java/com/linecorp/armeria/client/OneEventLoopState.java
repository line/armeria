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

import java.util.ArrayList;
import java.util.List;

import io.netty.channel.EventLoop;

final class OneEventLoopState extends AbstractEventLoopState {

    private final List<AbstractEventLoopEntry> entry = new ArrayList<>();

    private int allActiveRequests;

    OneEventLoopState(List<EventLoop> eventLoops, DefaultEventLoopScheduler scheduler) {
        super(eventLoops, scheduler);
    }

    @Override
    synchronized AbstractEventLoopEntry acquire() {
        if (entry.isEmpty()) {
            entry.add(new Entry(this, eventLoops().get(scheduler().acquisitionStartIndex(1))));
        }
        final AbstractEventLoopEntry e = entry.get(0);
        allActiveRequests++;
        return e;
    }

    @Override
    synchronized void release(AbstractEventLoopEntry e) {
        if (--allActiveRequests == 0) {
            setLastActivityTimeNanos();
        }
    }

    @Override
    List<AbstractEventLoopEntry> entries() {
        return entry;
    }

    @Override
    int allActiveRequests() {
        return allActiveRequests;
    }

    private static final class Entry extends AbstractEventLoopEntry {
        Entry(AbstractEventLoopState parent, EventLoop eventLoop) {
            super(parent, eventLoop);
        }

        @Override
        int activeRequests() {
            throw new UnsupportedOperationException();
        }

        @Override
        void incrementActiveRequests() {
            throw new UnsupportedOperationException();
        }

        @Override
        void decrementActiveRequests() {
            throw new UnsupportedOperationException();
        }

        @Override
        int id() {
            throw new UnsupportedOperationException();
        }

        @Override
        int index() {
            throw new UnsupportedOperationException();
        }

        @Override
        void setIndex(int index) {
            throw new UnsupportedOperationException();
        }
    }
}
