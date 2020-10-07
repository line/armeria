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

import static com.linecorp.armeria.client.DefaultEventLoopSchedulerTest.acquireEntry;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

class MaxNumEventLoopsPerEndpointTest {

    private static final Endpoint endpointA = Endpoint.of("a");
    private static final Endpoint endpointA80 = Endpoint.of("a", 80);
    private static final Endpoint endpointA443 = Endpoint.of("a", 443);
    private static final Endpoint endpointA8443 = Endpoint.of("a", 8443);
    private static final Endpoint endpointB = Endpoint.of("b");
    private static final Endpoint endpointB80 = Endpoint.of("b", 80);
    private static final Endpoint endpointB443 = Endpoint.of("b", 443);
    private static final Endpoint endpointB8443 = Endpoint.of("b", 8443);
    private static final Endpoint endpointC = Endpoint.of("c");

    @Test
    void defaultMaxNumEventLoopsEqualsOne() {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, 0, 0, ImmutableList.of());
        final List<AbstractEventLoopEntry> entries1 = s.entries(SessionProtocol.H1C, endpointA, endpointA);
        assertThat(entries1).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1C, endpointA, endpointA);
        assertThat(entries1).hasSize(1);
    }

    @Test
    void singleEndpointMaxNumEventLoops() {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(
                group, 4, 5, ImmutableList.of(endpoint -> {
            if (endpoint.equals(endpointA)) {
                return 3;
            } else {
                return -1;
            }
        }));
        checkMaxNumEventLoops(s, endpointA, endpointB);
    }

    private static void checkMaxNumEventLoops(DefaultEventLoopScheduler s,
                                              Endpoint preDefined, Endpoint undefined) {
        final List<AbstractEventLoopEntry> entries1 = s.entries(SessionProtocol.H1C, preDefined, preDefined);
        assertThat(entries1).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1C, preDefined, preDefined);
        assertThat(entries1).hasSize(3);

        final List<AbstractEventLoopEntry> entries2 = s.entries(SessionProtocol.H2, undefined, undefined);
        assertThat(entries2).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H2, undefined, undefined);
        assertThat(entries2).hasSize(4);

        final List<AbstractEventLoopEntry> entries3 = s.entries(SessionProtocol.H2C, undefined, undefined);
        assertThat(entries2).isNotSameAs(entries3);
        assertThat(entries3).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H2C, undefined, undefined);
        assertThat(entries3).hasSize(4);

        final List<AbstractEventLoopEntry> entries4 = s.entries(SessionProtocol.H1, undefined, undefined);
        assertThat(entries4).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1, undefined, undefined);
        assertThat(entries4).hasSize(5);

        final List<AbstractEventLoopEntry> entries5 = s.entries(SessionProtocol.H1C, undefined, undefined);
        assertThat(entries4).isNotSameAs(entries5);
        assertThat(entries5).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1C, undefined, undefined);
        assertThat(entries5).hasSize(5);
    }

    @Test
    void maxNumEventLoopsFunction() {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);

        final List<ToIntFunction<Endpoint>> maxNumEventLoopsFunctions = ImmutableList.of(
                endpoint -> {
                    if ("a".equals(endpoint.host())) {
                        if (endpoint.hasPort()) {
                            final int port = endpoint.port();
                            if (port == 80) {
                                return 2;
                            }
                            if (port == 8443) {
                                return 3;
                            }
                        }
                        return 1;
                    }
                    return -1;
                },
                endpoint -> {
                    if (endpoint.equals(endpointB80)) {
                        return 4;
                    }
                    if (endpoint.equals(endpointB443)) {
                        return 5;
                    }
                    return -1;
                });
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, 7, 7,
                                                                          maxNumEventLoopsFunctions);
        final List<AbstractEventLoopEntry> entries1 = s.entries(SessionProtocol.H1C, endpointA, endpointA);
        assertThat(entries1).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1C, endpointA, endpointA);
        assertThat(entries1).hasSize(2);

        final List<AbstractEventLoopEntry> entries2 = s.entries(SessionProtocol.H1C, endpointA80, endpointA80);
        assertThat(entries2).hasSize(2);

        final List<AbstractEventLoopEntry> entries3 =
                s.entries(SessionProtocol.H1C, endpointA443, endpointA443);
        assertThat(entries3).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1C, endpointA443, endpointA443);
        assertThat(entries3).hasSize(1); // Fallback to "a.com"

        final List<AbstractEventLoopEntry> entries4 =
                s.entries(SessionProtocol.H1C, endpointA8443, endpointA8443);
        assertThat(entries4).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1C, endpointA8443, endpointA8443);
        assertThat(entries4).hasSize(3); // Matched to Endpoint.of("a.com", 36462)

        // Clear text SessionProtocols.

        final List<AbstractEventLoopEntry> bComClearText =
                s.entries(SessionProtocol.H1C, endpointB80, endpointB80);
        assertThat(bComClearText).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1C, endpointB, endpointB);
        assertThat(bComClearText).hasSize(4); // Fallback to "b.com:80"

        final List<AbstractEventLoopEntry> entries5 = s.entries(SessionProtocol.H1C, endpointB, endpointB);
        assertThat(bComClearText).isSameAs(entries5);

        final List<AbstractEventLoopEntry> entries6 = s.entries(SessionProtocol.H2C, endpointB, endpointB);
        acquireTenEntries(s, SessionProtocol.H2C, endpointB, endpointB);
        assertThat(bComClearText).hasSize(4);
        final List<AbstractEventLoopEntry> entries7 = s.entries(SessionProtocol.HTTP, endpointB, endpointB);
        assertThat(entries6).isSameAs(entries7);

        // TLS SessionProtocols.

        final List<AbstractEventLoopEntry> bComTls = s.entries(SessionProtocol.H1, endpointB443, endpointB443);
        assertThat(bComTls).hasSize(0);
        acquireTenEntries(s, SessionProtocol.H1, endpointB, endpointB);
        assertThat(bComTls).hasSize(5); // Fallback to "b.com:433"

        final List<AbstractEventLoopEntry> entries8 = s.entries(SessionProtocol.H1, endpointB, endpointB);
        assertThat(bComTls).isSameAs(entries8);

        final List<AbstractEventLoopEntry> entries9 = s.entries(SessionProtocol.H2, endpointB, endpointB);
        acquireTenEntries(s, SessionProtocol.H2, endpointB, endpointB);
        assertThat(entries9).hasSize(5);
        final List<AbstractEventLoopEntry> entries10 = s.entries(SessionProtocol.HTTPS, endpointB, endpointB);
        assertThat(entries9).isSameAs(entries10);

        final List<AbstractEventLoopEntry> entries11 =
                s.entries(SessionProtocol.H1, endpointB8443, endpointB8443);
        assertThat(entries11).hasSize(
                1); // One entry is pushed when eventLoops.size() == maxNumEventLoops
        acquireTenEntries(s, SessionProtocol.H1, endpointB8443, endpointB8443);
        assertThat(entries11).hasSize(7); // No match
    }

    private static void acquireTenEntries(DefaultEventLoopScheduler s,
                                          SessionProtocol sessionProtocol,
                                          EndpointGroup endpointGroup,
                                          Endpoint endpoint) {
        for (int i = 0; i < 10; i++) {
            s.acquire(sessionProtocol, endpointGroup, endpoint);
        }
    }

    /**
     * This illustrates when the eventLoop at 0 index is selected from the first acquireEntry(s, endpointA)
     * call.
     * eventLoops idx:   [   0,   1,   2,   3,   4,   5,   6]
     *    - endpointA:       A    A
     *    - endpointB:                 B    B    B
     *    - endpointC:       C    C    C              C    C
     *
     * <P>The event loop group for endpoints is assigned sequentially. The endpointC starts at 5 requiring 5
     * event loops, so it wraps around at the end of the array and takes the first 3.
     */
    @Test
    void eventLoopGroupAssignedSequentially() {
        final List<ToIntFunction<Endpoint>> maxNumEventLoopsFunctions = ImmutableList.of(
                endpoint -> {
                    if (endpoint.equals(endpointA)) {
                        return 2;
                    }
                    if (endpoint.equals(endpointB)) {
                        return 3;
                    }
                    if (endpoint.equals(endpointC)) {
                        return 5;
                    }
                    return -1;
                });
        final int maxNumEventLoops = 7;
        checkEventLoopAssignedSequentially(maxNumEventLoopsFunctions, maxNumEventLoops);
    }

    /**
     * This illustrates when the eventLoop at 0 index is selected from the first acquireEntry(s, endpointA)
     * call.
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
        final List<ToIntFunction<Endpoint>> maxNumEventLoopsFunctions = ImmutableList.of(
                endpoint -> {
                    if (endpoint.equals(endpointA)) {
                        return 2;
                    }
                    if (endpoint.equals(endpointB)) {
                        return 3;
                    }
                    return -1;
                });
        final int maxNumEventLoops = 5;
        checkEventLoopAssignedSequentially(maxNumEventLoopsFunctions, maxNumEventLoops);
    }

    private static void checkEventLoopAssignedSequentially(
            List<ToIntFunction<Endpoint>> maxNumEventLoopsFunctions, int maxNumEventLoops) {
        final EventLoopGroup group = new DefaultEventLoopGroup(7);
        final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group, maxNumEventLoops,
                                                                          maxNumEventLoops,
                                                                          maxNumEventLoopsFunctions);

        // endpointA

        final EventLoop firstEventLoopA = acquireEntry(s, endpointA).get();
        acquireEntries(1, s, endpointA, firstEventLoopA);
        // After one circle, the next event loop is the first one.
        assertThat(firstEventLoopA).isSameAs(acquireEntry(s, endpointA).get());

        // endpointB

        final EventLoop firstEventLoopB = acquireEntry(s, endpointB).get();
        acquireEntries(2, s, endpointB, firstEventLoopB);
        // After one circle, the next event loop is the first one.
        assertThat(firstEventLoopB).isSameAs(acquireEntry(s, endpointB).get());

        // endpointC

        final EventLoop firstEventLoopC = acquireEntry(s, endpointC).get();
        acquireEntries(4, s, endpointC, firstEventLoopC);
        // After one circle, the next event loop is the first one.
        assertThat(firstEventLoopC).isSameAs(acquireEntry(s, endpointC).get());
    }

    private static void acquireEntries(int times, DefaultEventLoopScheduler s, Endpoint endpoint,
                                       EventLoop firstEventLoop) {
        for (int i = 0; i < times; i++) {
            assertThat(acquireEntry(s, endpoint).get()).isNotSameAs(firstEventLoop);
        }
    }
}
