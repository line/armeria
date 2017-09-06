/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.util.ReleasableHolder;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

final class EventLoopScheduler {

    private static final long CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final List<EventLoop> eventLoops;
    private final Map<String, State> map = new ConcurrentHashMap<>();
    private int counter;
    private volatile long lastCleanupTimeNanos = System.nanoTime();

    EventLoopScheduler(EventLoopGroup eventLoopGroup) {
        eventLoops = Streams.stream(eventLoopGroup)
                            .map(EventLoop.class::cast)
                            .collect(toImmutableList());
    }

    Entry acquire(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        final State state = state(endpoint);
        final Entry acquired = state.acquire();
        cleanup();
        return acquired;
    }

    @VisibleForTesting
    List<Entry> entries(Endpoint endpoint) {
        return state(endpoint).entries();
    }

    private State state(Endpoint endpoint) {
        final String authority = endpoint.authority();
        return map.computeIfAbsent(authority, e -> new State(eventLoops));
    }

    /**
     * Cleans up empty entries with no activity for more than 1 minute. For reduced overhead, we perform this
     * only when 1) the last clean-up was more than 1 minute ago and 2) the number of acquisitions % 256 is 0.
     */
    private void cleanup() {
        if ((++counter & 0xFF) != 0) { // (++counter % 256) != 0
            return;
        }

        final long currentTimeNanos = System.nanoTime();
        if (currentTimeNanos - lastCleanupTimeNanos < CLEANUP_INTERVAL_NANOS) {
            return;
        }

        for (Iterator<State> i = map.values().iterator(); i.hasNext();) {
            final State state = i.next();
            final boolean remove;

            synchronized (state) {
                remove = state.allActiveRequests == 0 &&
                         currentTimeNanos - state.lastActivityTimeNanos >= CLEANUP_INTERVAL_NANOS;
            }

            if (remove) {
                i.remove();
            }
        }

        lastCleanupTimeNanos = System.nanoTime();
    }

    private static final class State {
        /**
         * A binary heap of Entry. Ordered by:
         * <ul>
         *   <li>{@link Entry#activeRequests()} (lower is better)</li>
         *   <li>{@link Entry#id()} (lower is better)</li>
         * </ul>
         */
        private final List<Entry> entries;
        private final List<EventLoop> eventLoops;
        private int nextUnusedEventLoopIdx;
        private int allActiveRequests;

        /**
         * Updated only when {@link #allActiveRequests} is 0 by {@link #release(Entry)}.
         */
        private long lastActivityTimeNanos = System.nanoTime();

        State(List<EventLoop> eventLoops) {
            this.eventLoops = eventLoops;
            entries = new ArrayList<>();
            nextUnusedEventLoopIdx = ThreadLocalRandom.current().nextInt(eventLoops.size());
            addUnusedEventLoop();
        }

        List<Entry> entries() {
            return entries;
        }

        synchronized Entry acquire() {
            Entry e = entries.get(0);
            if (e.activeRequests() > 0) {
                // All event loops are handling connections; try to add an unused event loop.
                if (addUnusedEventLoop()) {
                    e = entries.get(0);
                    assert e.activeRequests() == 0;
                }
            }

            assert e.index() == 0;
            e.activeRequests++;
            allActiveRequests++;
            bubbleDown(0);
            return e;
        }

        private boolean addUnusedEventLoop() {
            if (entries.size() < eventLoops.size()) {
                push(new Entry(this, eventLoops.get(nextUnusedEventLoopIdx), entries.size()));
                nextUnusedEventLoopIdx = (nextUnusedEventLoopIdx + 1) % eventLoops.size();
                return true;
            } else {
                return false;
            }
        }

        synchronized void release(Entry e) {
            assert e.parent() == this;
            e.activeRequests--;
            bubbleUp(e.index());
            if (--allActiveRequests == 0) {
                lastActivityTimeNanos = System.nanoTime();
            }
        }

        // Heap implementation, modified from the public domain code at https://stackoverflow.com/a/714873
        private void push(Entry e) {
            entries.add(e);
            bubbleUp(entries.size() - 1);
        }

        private void bubbleDown(int i) {
            int best = i;
            for (;;) {
                final int oldBest = best;
                final int left = left(best);

                if (left < entries.size()) {
                    final int right = right(best);
                    if (isBetter(left, best)) {
                        if (right < entries.size()) {
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
                    } else if (right < entries.size()) {
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
            final Entry entryA = entries.get(a);
            final Entry entryB = entries.get(b);
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
            final Entry entryI = entries.get(i);
            final Entry entryJ = entries.get(j);
            entries.set(i, entryJ);
            entries.set(j, entryI);

            // Swap the index as well.
            entryJ.setIndex(i);
            entryI.setIndex(j);
        }

        @Override
        public String toString() {
            return '[' + Joiner.on(", ").join(entries) + ']';
        }
    }

    static final class Entry implements ReleasableHolder<EventLoop> {
        private final State parent;
        private final EventLoop eventLoop;
        private final int id;
        private int activeRequests;

        /**
         * Index in the binary heap {@link State#entries}. Updated by {@link State#swap(int, int)} after
         * {@link #activeRequests} is updated by {@link State#acquire()} and {@link State#release(Entry)}.
         */
        private int index;

        Entry(State parent, EventLoop eventLoop, int id) {
            this.parent = parent;
            this.eventLoop = eventLoop;
            this.id = index = id;
        }

        @Override
        public EventLoop get() {
            return eventLoop;
        }

        State parent() {
            return parent;
        }

        int id() {
            return id;
        }

        int index() {
            return index;
        }

        void setIndex(int index) {
            this.index = index;
        }

        int activeRequests() {
            return activeRequests;
        }

        @Override
        public void release() {
            parent.release(this);
        }

        @Override
        public String toString() {
            return "(" + index + ", " + id + ", " + activeRequests + ')';
        }
    }
}
