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
/*
 * Copyright (c)  2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.SubscriberWhiteboxVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

import reactor.core.publisher.Flux;

public class MultipartEncoderSubsWhiteBoxTckTest extends SubscriberWhiteboxVerification<BodyPart> {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/media/multipart/src/test/java/io/helidon/media/multipart/MultiPartEncoderSubsWhiteBoxTckTest.java

    protected MultipartEncoderSubsWhiteBoxTckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public BodyPart createElement(final int element) {
        return BodyPart.builder()
                       .content("part" + element)
                       .build();
    }

    @Override
    public Subscriber<BodyPart> createSubscriber(WhiteboxSubscriberProbe<BodyPart> probe) {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final MultipartEncoder encoder = new MultipartEncoder("boundary") {
            @Override
            public void onSubscribe(Subscription subscription) {
                super.onSubscribe(subscription);
                future.complete(null);
                probe.registerOnSubscribe(new SubscriberPuppet() {
                    @Override
                    public void triggerRequest(final long elements) {
                        subscription.request(elements);
                    }

                    @Override
                    public void signalCancel() {
                        subscription.cancel();
                    }
                });
            }

            @Override
            public void onNext(final BodyPart bodyPart) {
                super.onNext(bodyPart);
                probe.registerOnNext(bodyPart);
            }

            @Override
            public void onError(final Throwable throwable) {
                super.onError(throwable);
                probe.registerOnError(throwable);
            }

            @Override
            public void onComplete() {
                super.onComplete();
                probe.registerOnComplete();
            }
        };

        Flux.from(encoder).subscribe(ch -> {});
        return encoder;
    }

    @Test(enabled = false)
    @Override
    @SuppressWarnings("checkstyle:LineLength")
    public void required_spec205_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal()
            throws Throwable {
        // not compliant
    }
}
