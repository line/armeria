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
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.base.Joiner;

import io.netty.channel.EventLoop;

final class HeapBasedEventLoopState extends AbstractEventLoopState {

    /**
     * A binary heap of Entry. Ordered by:
     * <ul>
     *   <li>{@link AbstractEventLoopEntry#activeRequests()} (lower is better)</li>
     *   <li>{@link AbstractEventLoopEntry#id()} (lower is better)</li>
     * </ul>
     */
    private final AbstractEventLoopEntry[] entries;
    private int entriesSize;
    private final int maxNumEventLoops;

    private int acquisitionStartIndex = -1;
    private int nextUnusedEventLoopOffset;
    private int allActiveRequests;

    HeapBasedEventLoopState(List<EventLoop> eventLoops, int maxNumEventLoops,
                            DefaultEventLoopScheduler scheduler) {
        super(eventLoops, scheduler);
        this.maxNumEventLoops = maxNumEventLoops;
        entries = new AbstractEventLoopEntry[maxNumEventLoops];
        if (eventLoops.size() == maxNumEventLoops) {
            // We use all event loops so initialize early.
            init(0);
        }
    }

    private void init(int acquisitionStartIndex) {
        this.acquisitionStartIndex = acquisitionStartIndex;
        nextUnusedEventLoopOffset = ThreadLocalRandom.current().nextInt(maxNumEventLoops);
        addUnusedEventLoop();
    }

    private boolean addUnusedEventLoop() {
        if (entriesSize < maxNumEventLoops) {
            final int nextIndex = (acquisitionStartIndex + nextUnusedEventLoopOffset) %
                                  eventLoops().size();
            push(new Entry(this, eventLoops().get(nextIndex), entriesSize));
            nextUnusedEventLoopOffset = (nextUnusedEventLoopOffset + 1) % maxNumEventLoops;
            return true;
        }
        return false;
    }

    @Override
    AbstractEventLoopEntry[] entries() {
        return entries;
    }

    @Override
    int allActiveRequests() {
        return allActiveRequests;
    }

    @Override
    AbstractEventLoopEntry acquire() {
        lock();
        try {
            if (acquisitionStartIndex == -1) {
                init(scheduler().acquisitionStartIndex(maxNumEventLoops));
            }
            AbstractEventLoopEntry e = entries[0];
            if (e.activeRequests() > 0) {
                // All event loops are handling connections; try to add an unused event loop.
                if (addUnusedEventLoop()) {
                    e = entries[0];
                    assert e.activeRequests() == 0;
                }
            }

            assert e.index() == 0;
            e.incrementActiveRequests();
            allActiveRequests++;
            bubbleDown();
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
            bubbleUp(e.index());
            if (--allActiveRequests == 0) {
                setLastActivityTimeNanos();
            }
        } finally {
            unlock();
        }
    }

    // Heap implementation, modified from the public domain code at https://stackoverflow.com/a/714873
    private void push(Entry e) {
        entries[entriesSize++] = e;
        bubbleUp(entriesSize - 1);
    }

    private void bubbleDown() {
        int best = 0;
        for (;;) {
            final int oldBest = best;
            final int left = left(best);

            if (left < entriesSize) {
                final int right = right(best);
                if (isBetter(left, best)) {
                    if (right < entriesSize) {
                        if (isBetter(right, left)) {
                            // Left leaf is better but right leaf is even better.
                            best = right;
                        } else {
                            // Left leaf is better than the current entry and right left.
                            best = left;
                        }
                    } else {
                        // Left leaf is better and there's no right leaf.
                        best = left;
                    }
                } else if (right < entriesSize) {
                    if (isBetter(right, best)) {
                        // Left leaf is not better but right leaf is better.
                        best = right;
                    } else {
                        // Both left and right leaves are not better.
                        break;
                    }
                } else {
                    // Left leaf is not better and there's no right leaf.
                    break;
                }
            } else {
                // There are no leaves, because right leaf can't be present if left leaf isn't.
                break;
            }

            swap(best, oldBest);
        }
    }

    private void bubbleUp(int i) {
        while (i > 0) {
            final int parent = parent(i);
            if (isBetter(parent, i)) {
                break;
            }

            swap(parent, i);
            i = parent;
        }
    }

    /**
     * Returns {@code true} if the entry at {@code a} is a better choice than the entry at {@code b}.
     */
    private boolean isBetter(int a, int b) {
        final AbstractEventLoopEntry entryA = entries[a];
        final AbstractEventLoopEntry entryB = entries[b];
        if (entryA.activeRequests() < entryB.activeRequests()) {
            return true;
        }
        if (entryA.activeRequests() > entryB.activeRequests()) {
            return false;
        }

        return entryA.id() < entryB.id();
    }

    private static int parent(int i) {
        return (i - 1) / 2;
    }

    private static int left(int i) {
        return 2 * i + 1;
    }

    private static int right(int i) {
        return 2 * i + 2;
    }

    private void swap(int i, int j) {
        final AbstractEventLoopEntry entryI = entries[i];
        final AbstractEventLoopEntry entryJ = entries[j];
        entries[i] = entryJ;
        entries[j] = entryI;

        // Swap the index as well.
        entryJ.setIndex(i);
        entryI.setIndex(j);
    }

    @Override
    public String toString() {
        return '[' + Joiner.on(", ").join(entries) + ']';
    }

    private static final class Entry extends AbstractEventLoopEntry {

        private final int id;

        /**
         * Index in the binary heap {@link HeapBasedEventLoopState#entries}.
         * Updated by {@link HeapBasedEventLoopState#swap(int, int)} after
         * {@link #activeRequests()} is updated by {@link HeapBasedEventLoopState#acquire()} and
         * {@link HeapBasedEventLoopState#release(AbstractEventLoopEntry)}.
         */
        private int index;

        private int activeRequests;

        Entry(AbstractEventLoopState parent, EventLoop eventLoop, int id) {
            super(parent, eventLoop);
            this.id = index = id;
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
            return index;
        }

        @Override
        void setIndex(int index) {
            this.index = index;
        }

        @Override
        public String toString() {
            return "(" + index + ", " + id + ", " + activeRequests() + ')';
        }
    }
}
