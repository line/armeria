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

package com.linecorp.armeria.common.stream;

import java.util.concurrent.atomic.AtomicLong;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

public class EventLoopStreamMessageVerification extends StreamMessageVerification<Long> {

    private static EventLoop eventLoop;

    @BeforeClass
    public static void startEventLoop() {
        eventLoop = new DefaultEventLoop();
    }

    @AfterClass
    public static void stopEventLoop() {
        eventLoop.shutdownGracefully().syncUninterruptibly();
    }

    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        return createStreamMessage(elements, false);
    }

    private static StreamMessage<Long> createStreamMessage(long elements, boolean abort) {
        final EventLoopStreamMessage<Long> stream = new EventLoopStreamMessage<>(eventLoop);
        if (elements == 0) {
            if (abort) {
                stream.abort();
            } else {
                stream.close();
            }
            return stream;
        }

        final AtomicLong remaining = new AtomicLong(elements);
        stream(elements, abort, remaining, stream);
        return stream;
    }

    private static void stream(long elements, boolean abort,
                               AtomicLong remaining, EventLoopStreamMessage<Long> stream) {
        stream.onDemand(() -> {
            for (;;) {
                final long r = remaining.decrementAndGet();
                final boolean written = stream.write(elements - r);
                if (r == 0) {
                    if (abort) {
                        stream.abort();
                    } else {
                        stream.close();
                    }
                    break;
                }

                if (!written) {
                    stream(elements, abort, remaining, stream);
                    break;
                }
            }
        });
    }

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        EventLoopStreamMessage<Long> stream = new EventLoopStreamMessage<Long>(eventLoop);
        stream.subscribe(new NoopSubscriber<>());
        return stream;
    }

    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        return createStreamMessage(elements, true);
    }

    // createStreamMessage creates a publisher that relies on onDemand for triggering writes - unfortunately
    // means that it is not possible to have reads and writes to the stream happening synchronously in the same
    // call tree, so this test passes regardless of whether the stream message actually correctly handles
    // recursion. We disable it here to prevent a false sense of security and verify the behavior in
    // AbstractStreamMessageTest.flowControlled_writeThenDemandThenProcess.
    @Override
    @Test(enabled = false)
    public void required_spec303_mustNotAllowUnboundedRecursion() throws Throwable {
        notVerified();
    }
}
