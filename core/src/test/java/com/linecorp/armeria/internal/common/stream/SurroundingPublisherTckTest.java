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

package com.linecorp.armeria.internal.common.stream;

import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import org.reactivestreams.Subscription;
import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.support.PublisherVerificationRules;
import org.testng.SkipException;
import org.testng.annotations.Test;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageVerification;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SuppressWarnings("checkstyle:LineLength")
public class SurroundingPublisherTckTest extends StreamMessageVerification<Object> {

    public SurroundingPublisherTckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public StreamMessage<Object> createPublisher(long elements) {
        if (elements == 0) {
            final StreamMessage<Object> publisher = new SurroundingPublisher<>(null, Mono.empty(), unused -> null);
            // `SurroundingPublisher` doesn't check head, tail, publisher's availability before subscribed so manually set complete.
            publisher.whenComplete().complete(null);
            return publisher;
        }
        if (elements == 1) {
            return new SurroundingPublisher<>("head", Mono.empty(), unused -> null);
        }
        if (elements == 2) {
            return new SurroundingPublisher<>(null, Mono.just(1), unused -> "tail");
        }
        return new SurroundingPublisher<>("head", Flux.fromStream(LongStream.range(0, elements - 2).boxed()), unused -> "tail");
    }

    /**
     * Rule 1.4 and 1.9 ensure a Publisher's ability to signal error to the Subscriber, however the
     * implementation expects such error to occur immediately after subscribing, i.e. {@code onError()} is
     * called after {@code onSubscribe()}.
     * The {@link SurroundingPublisher} however subscribes publisher and signals error after the first request,
     * therefore for the error to be signaled, we must make requests first.
     *
     * {@link PublisherVerificationRules#optional_spec104_mustSignalOnErrorWhenFails()} and
     * {@link PublisherVerificationRules#required_spec109_mayRejectCallsToSubscribeIfPublisherIsUnableOrUnwillingToServeThemRejectionMustTriggerOnErrorAfterOnSubscribe()}
     * are overridden below to call {@link Subscription#request(long)} after subscribing.
     */
    @Override
    public StreamMessage<Object> createFailedPublisher() {
        return new SurroundingPublisher<>(null, Mono.error(new RuntimeException()), unused -> null);
    }

    @Override
    public @Nullable StreamMessage<Object> createAbortedPublisher(long elements) {
        if (elements == 0) {
            final StreamMessage<Object> publisher = new SurroundingPublisher<>(null, Mono.empty(), unused -> null);
            publisher.abort();
            return publisher;
        }

        final StreamMessage<Object> publisher = createPublisher(LongMath.saturatedAdd(elements, 1));
        final AtomicLong produced = new AtomicLong();
        return publisher
                .peek(item -> {
                    if (produced.getAndIncrement() >= elements) {
                        publisher.abort();
                    }
                });
    }

    @Test
    @Override
    public void optional_spec104_mustSignalOnErrorWhenFails() {
        try {
            final TestEnvironment env = new TestEnvironment(200);
            whenHasErrorPublisherTest(pub -> {
                final TestEnvironment.Latch onErrorLatch = new TestEnvironment.Latch(env);
                final TestEnvironment.Latch onSubscribeLatch = new TestEnvironment.Latch(env);
                pub.subscribe(new TestEnvironment.TestSubscriber<Object>(env) {
                    @Override
                    public void onSubscribe(Subscription subs) {
                        onSubscribeLatch.assertOpen("Only one onSubscribe call expected");
                        onSubscribeLatch.close();
                        subs.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onError(Throwable cause) {
                        onSubscribeLatch.assertClosed("onSubscribe should be called prior to onError always");
                        onErrorLatch.assertOpen(String.format("Error-state Publisher %s called `onError` twice on new Subscriber", pub));
                        onErrorLatch.close();
                    }
                });
                onSubscribeLatch.expectClose("Should have received onSubscribe");
                onErrorLatch.expectClose(String.format("Error-state Publisher %s did not call `onError` on new Subscriber", pub));

                env.verifyNoAsyncErrors();
            });
        } catch (SkipException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(String.format("Publisher threw exception (%s) instead of signalling error via onError!", t.getMessage()), t);
        }
    }

    @Test
    @Override
    public void required_spec109_mayRejectCallsToSubscribeIfPublisherIsUnableOrUnwillingToServeThemRejectionMustTriggerOnErrorAfterOnSubscribe() throws Throwable {
        final TestEnvironment env = new TestEnvironment(200);
        whenHasErrorPublisherTest(pub -> {
            final TestEnvironment.Latch onErrorLatch = new TestEnvironment.Latch(env);
            final TestEnvironment.Latch onSubscribeLatch = new TestEnvironment.Latch(env);
            pub.subscribe(new TestEnvironment.TestSubscriber<Object>(env) {
                @Override
                public void onSubscribe(Subscription subs) {
                    onSubscribeLatch.assertOpen("Only one onSubscribe call expected");
                    onSubscribeLatch.close();
                    subs.request(Long.MAX_VALUE);
                }

                @Override
                public void onError(Throwable cause) {
                    onSubscribeLatch.assertClosed("onSubscribe should be called prior to onError always");
                    onErrorLatch.assertOpen("Only one onError call expected");
                    onErrorLatch.close();
                }
            });
            onSubscribeLatch.expectClose("Should have received onSubscribe");
            onErrorLatch.expectClose("Should have received onError");

            env.verifyNoAsyncErrorsNoDelay();
        });
    }
}
