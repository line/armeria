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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.client.DefaultEventLoopSchedulerTest.acquireEntry;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

class MaxNumEventLoopsPerEndpointTest {

    @Test
    void defaultMaxNumEventLoopsEqualsOne() {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, 0, 0, ImmutableMap.of());
        final List<EventLoopEntry> entries1 = s.entries(Endpoint.of("a"), SessionProtocol.H1C);
        assertThat(entries1).hasSize(0);
        acquireTenEntries(s, Endpoint.of("a"), SessionProtocol.H1C);
        assertThat(entries1).hasSize(1);

        final List<EventLoopEntry> entries2 = s.entries(Endpoint.ofGroup("b"), SessionProtocol.H2C);
        assertThat(entries2).hasSize(0);
        acquireTenEntries(s, Endpoint.ofGroup("b"), SessionProtocol.H2C);
        assertThat(entries2).hasSize(1);
    }

    @Test
    void groupMaxNumEventLoops() {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);
        final Map<Endpoint, Integer> maxNumEventLoopsMap =
                ImmutableMap.of(Endpoint.ofGroup("a"), 3);
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, 4, 5, maxNumEventLoopsMap);
        checkMaxNumEventLoops(s, Endpoint.ofGroup("a"), Endpoint.ofGroup("b"));
    }

    @Test
    void singleEndpointMaxNumEventLoops() {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);
        final Map<Endpoint, Integer> maxNumEventLoopsMap =
                ImmutableMap.of(Endpoint.of("a"), 3);
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, 4, 5, maxNumEventLoopsMap);
        checkMaxNumEventLoops(s, Endpoint.of("a"), Endpoint.of("b"));
    }

    private static void checkMaxNumEventLoops(DefaultEventLoopScheduler s,
                                              Endpoint preDefined, Endpoint undefined) {
        final List<EventLoopEntry> entries1 = s.entries(preDefined, SessionProtocol.H1C);
        assertThat(entries1).hasSize(0);
        acquireTenEntries(s, preDefined, SessionProtocol.H1C);
        assertThat(entries1).hasSize(3);

        final List<EventLoopEntry> entries2 = s.entries(undefined, SessionProtocol.H2);
        assertThat(entries2).hasSize(0);
        acquireTenEntries(s, undefined, SessionProtocol.H2);
        assertThat(entries2).hasSize(4);

        final List<EventLoopEntry> entries3 = s.entries(undefined, SessionProtocol.H2C);
        assertThat(entries2).isNotSameAs(entries3);
        assertThat(entries3).hasSize(0);
        acquireTenEntries(s, undefined, SessionProtocol.H2C);
        assertThat(entries3).hasSize(4);

        final List<EventLoopEntry> entries4 = s.entries(undefined, SessionProtocol.H1);
        assertThat(entries4).hasSize(0);
        acquireTenEntries(s, undefined, SessionProtocol.H1);
        assertThat(entries4).hasSize(5);

        final List<EventLoopEntry> entries5 = s.entries(undefined, SessionProtocol.H1C);
        assertThat(entries4).isNotSameAs(entries5);
        assertThat(entries5).hasSize(0);
        acquireTenEntries(s, undefined, SessionProtocol.H1C);
        assertThat(entries5).hasSize(5);
    }

    @Test
    void maxNumEventLoopsMap() {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);

        final Map<Endpoint, Integer> maxNumEventLoopsMap =
                ImmutableMap.of(Endpoint.of("a.com"), 1,
                                Endpoint.of("a.com", 80), 2,
                                Endpoint.of("a.com", 36462), 3,
                                Endpoint.of("b.com", 80), 4,
                                Endpoint.of("b.com", 443), 5);
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, 7, 7, maxNumEventLoopsMap);
        final List<EventLoopEntry> entries1 = s.entries(Endpoint.of("a.com"), SessionProtocol.H1C);
        assertThat(entries1).hasSize(0);
        acquireTenEntries(s, Endpoint.of("a.com"), SessionProtocol.H1C);
        assertThat(entries1).hasSize(2); // Matched to Endpoint.of("a.com", 80)

        final List<EventLoopEntry> entries2 = s.entries(Endpoint.of("a.com", 80), SessionProtocol.H1C);
        assertThat(entries1).isSameAs(entries2);

        final List<EventLoopEntry> entries3 = s.entries(Endpoint.of("a.com", 10000), SessionProtocol.H1C);
        assertThat(entries3).hasSize(0);
        acquireTenEntries(s, Endpoint.of("a.com", 10000), SessionProtocol.H1C);
        assertThat(entries3).hasSize(1); // Fallback to "a.com"

        final List<EventLoopEntry> entries4 = s.entries(Endpoint.of("a.com", 36462), SessionProtocol.H1C);
        assertThat(entries4).hasSize(0);
        acquireTenEntries(s, Endpoint.of("a.com", 36462), SessionProtocol.H1C);
        assertThat(entries4).hasSize(3); // Matched to Endpoint.of("a.com", 36462)

        // Clear text SessionProtocols.

        final List<EventLoopEntry> bComClearText = s.entries(Endpoint.of("b.com", 80), SessionProtocol.H1C);
        assertThat(bComClearText).hasSize(0);
        acquireTenEntries(s, Endpoint.of("b.com"), SessionProtocol.H1C);
        assertThat(bComClearText).hasSize(4); // Fallback to "b.com:80"

        final List<EventLoopEntry> entries5 = s.entries(Endpoint.of("b.com"), SessionProtocol.H1C);
        assertThat(bComClearText).isSameAs(entries5);
        final List<EventLoopEntry> entries6 = s.entries(Endpoint.of("b.com"), SessionProtocol.H2C);
        assertThat(bComClearText).isSameAs(entries6);
        final List<EventLoopEntry> entries7 = s.entries(Endpoint.of("b.com"), SessionProtocol.HTTP);
        assertThat(bComClearText).isSameAs(entries7);

        // TLS SessionProtocols.

        final List<EventLoopEntry> bComTls = s.entries(Endpoint.of("b.com", 443), SessionProtocol.H1);
        assertThat(bComTls).hasSize(0);
        acquireTenEntries(s, Endpoint.of("b.com"), SessionProtocol.H1);
        assertThat(bComTls).hasSize(5); // Fallback to "b.com:433"

        final List<EventLoopEntry> entries8 = s.entries(Endpoint.of("b.com"), SessionProtocol.H1);
        assertThat(bComTls).isSameAs(entries8);
        final List<EventLoopEntry> entries9 = s.entries(Endpoint.of("b.com"), SessionProtocol.H2);
        assertThat(bComTls).isSameAs(entries9);
        final List<EventLoopEntry> entries10 = s.entries(Endpoint.of("b.com"), SessionProtocol.HTTPS);
        assertThat(bComTls).isSameAs(entries10);

        final List<EventLoopEntry> entries11 = s.entries(Endpoint.of("b.com", 8443), SessionProtocol.H1);
        assertThat(entries11).hasSize(1); // One entry is pushed when eventLoops.size() == maxNumEventLoops
        acquireTenEntries(s, Endpoint.of("b.com", 8443), SessionProtocol.H1);
        assertThat(entries11).hasSize(7); // No match
    }

    private static void acquireTenEntries(DefaultEventLoopScheduler s, Endpoint endpoint,
                                          SessionProtocol sessionProtocol) {
        for (int i = 0; i < 10; i++) {
            s.acquire(endpoint, sessionProtocol);
        }
    }

    /**
     * eventLoops idx:   [   0,   1,   2,   3,   4,   5,   6]
     *    - endpointA:       A    A
     *    - endpointB:                 B    B    B
     *    - endpointC:       C    C    C              C    C
     *
     * <P>The event loop group for endpoints is assigned sequentially. The endpointC needs 5 event loops so it
     * takes the 3 event loops from the first 3 elements in the list.
     */
    @Test
    void eventLoopGroupAssignedSequentially() {
        final Map<Endpoint, Integer> maxNumEventLoopsMap =
                ImmutableMap.of(Endpoint.of("a"), 2, Endpoint.of("b"), 3, Endpoint.of("c"), 5);
        final int maxNumEventLoops = 7;
        checkEventLoopAssignedSequentially(maxNumEventLoopsMap, maxNumEventLoops);
    }

    /**
     * eventLoops idx:   [   0,   1,   2,   3,   4,   5,   6]
     *    - endpointA:       A    A
     *    - endpointB:                 B    B    B
     *    - endpointC:       C    C    C              C    C
     *
     * <P>The size limit for the endpointC is not specified. So it will be 5 which
     * is the default one. The result is same with the {@link #eventLoopGroupAssignedSequentially()}.
     */
    @Test
    void eventLoopGroupAssignedSequentiallyWithDefaultMaxNumEventLoops() {
        final Map<Endpoint, Integer> maxNumEventLoopsMap =
                ImmutableMap.of(Endpoint.of("a"), 2, Endpoint.of("b"), 3);
        final int maxNumEventLoops = 5;
        checkEventLoopAssignedSequentially(maxNumEventLoopsMap, maxNumEventLoops);
    }

    private static void checkEventLoopAssignedSequentially(Map<Endpoint, Integer> maxNumEventLoopsMap,
                                                           int maxNumEventLoops) {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);
        final List<EventLoop> eventLoops = Streams.stream(group)
                                                  .map(EventLoop.class::cast)
                                                  .collect(toImmutableList());
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, maxNumEventLoops,
                                                                          maxNumEventLoops,
                                                                          maxNumEventLoopsMap);

        // endpointA

        final Endpoint endpointA = Endpoint.of("a");
        EventLoop firstEventLoop = acquireEntry(s, endpointA).get();
        int firstEventLoopIdx = findIndex(eventLoops, firstEventLoop);
        assertThat(firstEventLoopIdx).isIn(0, 1);
        checkNextEventLoopIdx(s, eventLoops, endpointA, firstEventLoopIdx, 0, 2);
        // After one circle, the next event loop is the first one.
        assertThat(firstEventLoop).isSameAs(acquireEntry(s, endpointA).get());

        // endpointB

        final Endpoint endpointB = Endpoint.of("b");
        firstEventLoop = acquireEntry(s, endpointB).get();
        firstEventLoopIdx = findIndex(eventLoops, firstEventLoop);
        assertThat(firstEventLoopIdx).isIn(2, 3, 4);
        checkNextEventLoopIdx(s, eventLoops, endpointB, firstEventLoopIdx, 2, 3);
        // After one circle, the next event loop is the first one.
        assertThat(firstEventLoop).isSameAs(acquireEntry(s, endpointB).get());

        // endpointC

        final Endpoint endpointC = Endpoint.of("c");
        firstEventLoop = acquireEntry(s, endpointC).get();
        firstEventLoopIdx = findIndex(eventLoops, firstEventLoop);
        assertThat(firstEventLoopIdx).isIn(0, 1, 2, 5, 6);
        checkNextEventLoopIdx(s, eventLoops, endpointC, firstEventLoopIdx, 5, 5);
        // After one circle, the next event loop is the first one.
        assertThat(firstEventLoop).isSameAs(acquireEntry(s, endpointC).get());
    }

    private static int findIndex(List<EventLoop> eventLoops, EventLoop eventLoop) {
        for (int i = 0; i < eventLoops.size(); i++) {
            if (eventLoops.get(i) == eventLoop) {
                return i;
            }
        }

        // Should never reach here.
        Assertions.fail("Could not find the eventLoop.");
        return -1;
    }

    private static void checkNextEventLoopIdx(DefaultEventLoopScheduler s, List<EventLoop> eventLoops,
                                              Endpoint endpoint, int firstEventLoopIdx, int startIdx,
                                              int sizeLimit) {
        int nextOffset = ((firstEventLoopIdx + eventLoops.size()) - startIdx) % eventLoops.size();
        for (int i = 1; i < sizeLimit; i++) {
            nextOffset++;
            if (nextOffset == sizeLimit) {
                nextOffset = 0;
            }
            final int nextIdx = (startIdx + nextOffset) % eventLoops.size();

            final EventLoop nextEventLoop = acquireEntry(s, endpoint).get();
            assertThat(nextEventLoop).isSameAs(eventLoops.get(nextIdx));
        }
    }
}
