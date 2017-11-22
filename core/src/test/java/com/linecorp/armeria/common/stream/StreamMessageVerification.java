/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.TestEnvironment.Latch;
import org.reactivestreams.tck.TestEnvironment.ManualSubscriber;
import org.reactivestreams.tck.TestEnvironment.TestSubscriber;
import org.testng.SkipException;
import org.testng.annotations.Test;

public abstract class StreamMessageVerification<T> extends PublisherVerification<T> {

    private final TestEnvironment env;

    protected StreamMessageVerification() {
        this(new TestEnvironment());
    }

    protected StreamMessageVerification(TestEnvironment env) {
        super(env);
        this.env = env;
    }

    @Override
    public abstract StreamMessage<T> createPublisher(long elements);

    @Override
    public abstract StreamMessage<T> createFailedPublisher();

    @Nullable
    public abstract StreamMessage<T> createAbortedPublisher(long elements);

    @Test
    public void required_completionFutureMustCompleteOnTermination0() throws Throwable {
        activePublisherTest(0, true, pub -> {
            final ManualSubscriber<T> sub = env.newManualSubscriber(pub);
            final StreamMessage<?> stream = (StreamMessage<?>) pub;

            if (!(stream instanceof PublisherBasedStreamMessage)) {
                // It's impossible for PublisherBasedStreamMessage to tell if the stream is
                // closed or empty yet because Publisher doesn't have enough information.
                assertThat(stream.isOpen()).isFalse();
                assertThat(stream.isEmpty()).isTrue();
            }

            assertThat(stream.completionFuture()).isNotDone();
            sub.requestEndOfStream();

            assertThat(stream.isOpen()).isFalse();
            assertThat(stream.isEmpty()).isTrue();
            assertThat(stream.completionFuture()).isCompleted();
            sub.expectNone();
        });
    }

    @Test
    public void required_completionFutureMustCompleteOnTermination1() throws Throwable {
        activePublisherTest(1, true, pub -> {
            final ManualSubscriber<T> sub = env.newManualSubscriber(pub);
            final StreamMessage<?> stream = (StreamMessage<?>) pub;
            assertThat(stream.isOpen()).isTrue();
            assertThat(stream.isEmpty()).isFalse();

            assertThat(stream.completionFuture()).isNotDone();
            sub.requestNextElement();
            sub.requestEndOfStream();
            assertThat(stream.completionFuture()).isCompleted();
            sub.expectNone();
        });
    }

    @Test
    public void required_completionFutureMustCompleteOnCancellation() throws Throwable {
        activePublisherTest(10, true, pub -> {
            final ManualSubscriber<T> sub = env.newManualSubscriber(pub);
            final StreamMessage<?> stream = (StreamMessage<?>) pub;

            assertThat(stream.completionFuture()).isNotDone();
            sub.requestNextElement();
            assertThat(stream.completionFuture()).isNotDone();
            sub.cancel();
            sub.expectNone();

            assertThat(stream.completionFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> stream.completionFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CancelledSubscriptionException.class);
        });
    }

    /**
     * Modified from {@link #optional_spec104_mustSignalOnErrorWhenFails()}.
     */
    @Test
    public void required_subscribeOnAbortedStreamMustFail() throws Throwable {
        final StreamMessage<T> pub = createAbortedPublisher(0);
        assumeAbortedPublisherAvailable(pub);
        assertThat(pub.isOpen()).isFalse();
        assertThatThrownBy(() -> pub.completionFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AbortedStreamException.class);

        final AtomicReference<Throwable> capturedCause = new AtomicReference<>();
        final Latch onErrorLatch = new Latch(env);
        final Latch onSubscribeLatch = new Latch(env);
        pub.subscribe(new TestSubscriber<T>(env) {
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

        env.verifyNoAsyncErrors();
        assertThat(capturedCause.get()).isInstanceOf(AbortedStreamException.class);
    }

    @Test
    public void required_abortMustNotifySubscriber() throws Throwable {
        final StreamMessage<T> pub = createAbortedPublisher(1);
        assumeAbortedPublisherAvailable(pub);
        assertThat(pub.isOpen()).isTrue();

        final ManualSubscriber<T> sub = env.newManualSubscriber(pub);
        sub.request(1); // First element
        assertThat(sub.nextElement()).isNotNull();
        sub.request(1); // Abortion
        sub.expectError(AbortedStreamException.class);

        env.verifyNoAsyncErrorsNoDelay();
        assertThatThrownBy(() -> pub.completionFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AbortedStreamException.class);
    }

    private void assumeAbortedPublisherAvailable(@Nullable Publisher<T> pub) {
        if (pub == null) {
            throw new SkipException("Skipping because no aborted StreamMessage provided.");
        }
    }
}
