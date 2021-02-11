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
import static org.awaitility.Awaitility.await;

import java.util.Objects;
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

import com.linecorp.armeria.common.SplitHttpResponse;

public abstract class StreamMessageVerification<T> extends PublisherVerification<T> {

    private final TestEnvironment env;

    protected StreamMessageVerification() {
        this(new TestEnvironment(1000, 200));
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

            if (!(stream instanceof PublisherBasedStreamMessage || stream instanceof SplitHttpResponse)) {
                // It's impossible for PublisherBasedStreamMessage to tell if the stream is
                // closed or empty yet because Publisher doesn't have enough information.
                assertThat(stream.isOpen()).isFalse();
                assertThat(stream.isEmpty()).isTrue();
            }

            if (!(stream instanceof SplitHttpResponse || stream instanceof PathStreamMessage)) {
                // - SplitHttpResponse could complete early to read HTTP headers from HttpResponse before
                //   publishing body
                // - PathStreamMessage immediately completes if a Path size is zero
                assertThat(stream.whenComplete()).isNotDone();
            }
            sub.requestEndOfStream();

            await().untilAsserted(() -> assertThat(stream.whenComplete()).isCompleted());
            assertThat(stream.isOpen()).isFalse();
            assertThat(stream.isEmpty()).isTrue();
            sub.expectNone();
        });
    }

    @Test
    public void required_completionFutureMustCompleteOnTermination1() throws Throwable {
        activePublisherTest(1, true, pub -> {
            final ManualSubscriber<T> sub = env.newManualSubscriber(pub);
            final StreamMessage<?> stream = (StreamMessage<?>) pub;
            if (!(pub instanceof FixedStreamMessage)) {
                // Fixed streams are never open.
                assertThat(stream.isOpen()).isTrue();
            }
            assertThat(stream.isEmpty()).isFalse();
            assertThat(stream.whenComplete()).isNotDone();

            sub.requestNextElement();
            sub.requestEndOfStream();

            stream.whenComplete().join();
            sub.expectNone();
        });
    }

    @Test
    public void required_completionFutureMustCompleteOnCancellation() throws Throwable {
        activePublisherTest(10, true, pub -> {
            final ManualSubscriber<T> sub = env.newManualSubscriber(pub);
            final StreamMessage<?> stream = (StreamMessage<?>) pub;

            assertThat(stream.whenComplete()).isNotDone();
            sub.requestNextElement();
            assertThat(stream.whenComplete()).isNotDone();
            sub.cancel();
            sub.expectNone();

            await().untilAsserted(() -> assertThat(stream.whenComplete()).isCompletedExceptionally());
            assertThatThrownBy(() -> stream.whenComplete().join())
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
        if (pub == null) {
            notVerified();
        }
        assumeAbortedPublisherAvailable(pub);
        assertThatThrownBy(() -> pub.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AbortedStreamException.class);
        assertThat(pub.isOpen()).isFalse();

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
        if (pub == null) {
            notVerified();
        }
        assumeAbortedPublisherAvailable(pub);
        if (!(pub instanceof FixedStreamMessage)) {
            // A fixed stream is never open.
            assertThat(pub.isOpen()).isTrue();
        }

        final ManualSubscriber<T> sub = env.newManualSubscriber(pub);
        sub.request(1); // An element or abortion

        boolean confirmedAbortion = false;
        Object element = null;
        try {
            element = sub.nextElement();
        } catch (AssertionError e) {
            // Case 1: Abortion occurred before the element is signaled.
            sub.expectError(AbortedStreamException.class);

            // Make sure the next error is 'e'. We did not do identity check here because
            // the TCK rebuilds the exception internally. See TestEnvironment.flopAndFail().
            final AssertionError e2 = sub.expectError(AssertionError.class);
            if (!Objects.equals(e2.getMessage(), e.getMessage())) {
                throw e2;
            }

            confirmedAbortion = true;
        }

        if (!confirmedAbortion) {
            // Case 2: The element was received before the abortion.
            assertThat(element).isNotNull();
            sub.request(1); // Abortion
            sub.expectError(AbortedStreamException.class);
        }

        env.verifyNoAsyncErrorsNoDelay();
        assertThatThrownBy(() -> pub.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AbortedStreamException.class);
    }

    private void assumeAbortedPublisherAvailable(@Nullable Publisher<T> pub) {
        if (pub == null) {
            throw new SkipException("Skipping because no aborted StreamMessage provided.");
        }
    }

    @Override
    public void optional_spec111_maySupportMultiSubscribe() throws Throwable {
        multiSubscribeUnsupported();
    }

    @Override
    public void optional_spec111_registeredSubscribersMustReceiveOnNextOrOnCompleteSignals() throws Throwable {
        multiSubscribeUnsupported();
    }

    @Override
    @SuppressWarnings("checkstyle:LineLength")
    public void optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingOneByOne() throws Throwable {
        multiSubscribeUnsupported();
    }

    @Override
    @SuppressWarnings("checkstyle:LineLength")
    public void optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfront() throws Throwable {
        multiSubscribeUnsupported();
    }

    @Override
    @SuppressWarnings("checkstyle:LineLength")
    public void optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfrontAndCompleteAsExpected() throws Throwable {
        multiSubscribeUnsupported();
    }

    private static void multiSubscribeUnsupported() {
        throw new SkipException(StreamMessage.class.getSimpleName() +
                                " does not support multiple subscribers.");
    }
}
