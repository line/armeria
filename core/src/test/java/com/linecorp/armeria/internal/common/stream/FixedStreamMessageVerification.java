/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import org.reactivestreams.Subscription;
import org.reactivestreams.tck.TestEnvironment.Latch;
import org.reactivestreams.tck.TestEnvironment.TestSubscriber;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageVerification;

public class FixedStreamMessageVerification extends StreamMessageVerification<Long> {
    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        return StreamMessage.of(LongStream.range(0, elements).boxed().toArray(Long[]::new));
    }

    // A fixed stream cannot fail.

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        return null;
    }

    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        final StreamMessage<Long> stream = createPublisher(elements);
        stream.abort();
        return stream;
    }

    @Override
    @Test(enabled = false)
    public void required_abortMustNotifySubscriber() throws Throwable {
        // Fixed streams are closed from the start and there isn't a good way to abort after onSubscribe in this
        // test (fixed streams do not have an onDemand method).
        notVerified();
    }

    @Override
    @Test(enabled = false)
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() throws Throwable {
        // Publishes Integer.MAX_VALUE values, which is not feasible with a FixedStreamMessage where the values
        // are all pre-allocated.
        notVerified();
    }

    /**
     * Modified from {@link #optional_spec104_mustSignalOnErrorWhenFails()}.
     */
    @Test
    public void required_subscribeOnAbortedStreamMustFail() throws Throwable {
        // EmptyFixedStreamMessage performs nothing on abortion.
        final StreamMessage<Long> pub = createAbortedPublisher(1);
        if (pub == null || pub instanceof EmptyFixedStreamMessage) {
            notVerified();
        }
        assumeAbortedPublisherAvailable(pub);
        assertThatThrownBy(() -> pub.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AbortedStreamException.class);
        assertThat(pub.isOpen()).isFalse();

        final AtomicReference<Throwable> capturedCause = new AtomicReference<>();
        final Latch onErrorLatch = new Latch(env());
        final Latch onSubscribeLatch = new Latch(env());
        pub.subscribe(new TestSubscriber<Long>(env()) {
            @Override
            public void onSubscribe(Subscription subs) {
                onSubscribeLatch.assertOpen("Only one onSubscribe call expected");
                onSubscribeLatch.close();
            }

            @Override
            public void onError(Throwable cause) {
                onSubscribeLatch.assertClosed("onSubscribe should be called prior to onError always");
                onErrorLatch.assertOpen(String.format(
                        "Error-state Publisher %s called `onError` twice on new Subscriber", pub));
                capturedCause.set(cause);
                onErrorLatch.close();
            }
        });

        onSubscribeLatch.expectClose("Should have received onSubscribe");
        onErrorLatch.expectClose(String.format(
                "Error-state Publisher %s did not call `onError` on new Subscriber", pub));

        env().verifyNoAsyncErrors();
        assertThat(capturedCause.get()).isInstanceOf(AbortedStreamException.class);
    }
}
