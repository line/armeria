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

import java.util.ArrayList;
import java.util.List;

import io.netty.channel.EventLoop;

final class ArrayBasedEventLoopState extends AbstractEventLoopState {

    private final List<AbstractEventLoopEntry> entries = new ArrayList<>();
    private final int maxNumEventLoops;

    private EventLoopAcquisitionIndex acquisitionIndex;
    private int nextVisitIndex;
    private int allActiveRequests;

    ArrayBasedEventLoopState(List<EventLoop> eventLoops, int maxNumEventLoops,
                             DefaultEventLoopScheduler scheduler) {
        super(eventLoops, scheduler);
        this.maxNumEventLoops = maxNumEventLoops;
        if (eventLoops.size() == maxNumEventLoops) {
            // We use all event loops so initialize early.
            init(0);
        }
    }

    private void init(int acquisitionStartIndex) {
        acquisitionIndex = new EventLoopAcquisitionIndex(
                acquisitionStartIndex, maxNumEventLoops, eventLoops().size());
        nextVisitIndex = 0;
        addUnusedEventLoop();
    }

    private boolean addUnusedEventLoop() {
        if (entries.size() < maxNumEventLoops) {
            push(new Entry(this, eventLoops().get(acquisitionIndex.nextAcquirableIndex()), entries.size()));
            return true;
        }
        return false;
    }

    @Override
    AbstractEventLoopEntry acquire() {
        lock();
        try {
            if (acquisitionIndex == null) {
                init(scheduler().acquisitionStartIndex(maxNumEventLoops));
            }

            for (int offset = 0; offset < entries.size(); offset++) {
                final int index = (nextVisitIndex + offset) % entries.size();
                final AbstractEventLoopEntry e = entries.get(index);
                if (e.activeRequests() == 0) {
                    nextVisitIndex = index;
                    break;
                }
            }
            if (entries.get(nextVisitIndex).activeRequests() > 0) {
                // All event loops are handling connections; try to add an unused event loop.
                if (addUnusedEventLoop()) {
                    final AbstractEventLoopEntry e = entries.get(nextVisitIndex);
                    assert e.activeRequests() == 0;
                }
            }

            final AbstractEventLoopEntry e = entries.get(nextVisitIndex);
            e.incrementActiveRequests();
            allActiveRequests++;
            nextVisitIndex = (nextVisitIndex + 1) % entries.size();
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
    List<AbstractEventLoopEntry> entries() {
        return entries;
    }

    @Override
    int allActiveRequests() {
        return allActiveRequests;
    }

    private void push(ArrayBasedEventLoopState.Entry e) {
        entries.add(e);
        nextVisitIndex = entries.size() - 1;
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
