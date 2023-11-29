/*
 * Copyright 2023 LINE Corporation
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
import java.util.concurrent.ThreadLocalRandom;

import io.netty.channel.EventLoop;

final class ArrayBasedEventLoopState extends AbstractEventLoopState {

    private final AbstractEventLoopEntry[] entries;
    private final int maxNumEventLoops;
    private int allActiveRequests;

    ArrayBasedEventLoopState(List<EventLoop> eventLoops, int maxNumEventLoops,
                             DefaultEventLoopScheduler scheduler) {
        super(eventLoops, scheduler);
        this.maxNumEventLoops = maxNumEventLoops;
        entries = new AbstractEventLoopEntry[maxNumEventLoops];
        if (eventLoops.size() == maxNumEventLoops) {
            init(0);
        } else {
            init(scheduler().acquisitionStartIndex(maxNumEventLoops));
        }
    }

    private void init(final int acquisitionStartIndex) {
        final int initialEventLoopOffset = ThreadLocalRandom.current().nextInt(maxNumEventLoops);
        final int eventLoopSize = eventLoops().size();
        for (int i = 0; i < maxNumEventLoops; ++i) {
            final int nextIndex = (acquisitionStartIndex + (initialEventLoopOffset + i) % maxNumEventLoops) %
                                  eventLoopSize;
            entries[i] = new Entry(this, eventLoops().get(nextIndex), i);
        }
    }

    private AbstractEventLoopEntry targetEntry() {
        int minActiveRequest = Integer.MAX_VALUE;
        int targetIndex = 0;
        for (int i = 0; i < maxNumEventLoops; ++i) {
            final AbstractEventLoopEntry e = entries[i];
            final int activeRequests = e.activeRequests();
            if (activeRequests == 0) {
                return e;
            }
            if (minActiveRequest > activeRequests) {
                minActiveRequest = activeRequests;
                targetIndex = i;
            }
        }
        return entries[targetIndex];
    }

    @Override
    AbstractEventLoopEntry acquire() {
        lock();
        try {
            final AbstractEventLoopEntry e = targetEntry();
            e.incrementActiveRequests();
            allActiveRequests++;
            return e;
        } finally {
            unlock();
        }
    }

    @Override
    void release(AbstractEventLoopEntry e) {
        lock();
        try {
            e.decrementActiveRequests();
            if (--allActiveRequests == 0) {
                setLastActivityTimeNanos();
            }
        } finally {
            unlock();
        }
    }

    @Override
    AbstractEventLoopEntry[] entries() {
        return entries;
    }

    @Override
    int allActiveRequests() {
        return allActiveRequests;
    }

    private static final class Entry extends AbstractEventLoopEntry {

        private final int id;

        private int activeRequests;

        Entry(AbstractEventLoopState parent, EventLoop eventLoop, int id) {
            super(parent, eventLoop);
            this.id = id;
        }

        @Override
        int activeRequests() {
            return activeRequests;
        }

        @Override
        void incrementActiveRequests() {
            activeRequests++;
        }

        @Override
        void decrementActiveRequests() {
            activeRequests--;
        }

        @Override
        int id() {
            return id;
        }

        @Override
        int index() {
            throw new UnsupportedOperationException();
        }

        @Override
        void setIndex(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return "(" + id + ", " + activeRequests() + ')';
        }
    }
}
