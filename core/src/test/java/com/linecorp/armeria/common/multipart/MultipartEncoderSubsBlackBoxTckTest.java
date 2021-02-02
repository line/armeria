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

package com.linecorp.armeria.common.multipart;

import org.reactivestreams.Subscriber;
import org.reactivestreams.tck.SubscriberBlackboxVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

import reactor.core.publisher.Flux;

public class MultipartEncoderSubsBlackBoxTckTest extends SubscriberBlackboxVerification<BodyPart> {

    protected MultipartEncoderSubsBlackBoxTckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public Subscriber<BodyPart> createSubscriber() {
        final MultipartEncoder encoder = new MultipartEncoder("boundary");
        Flux.from(encoder)
            .subscribe(ch -> {});
        return encoder;
    }

    @Override
    public BodyPart createElement(int element) {
        return BodyPart.builder()
                       .content("part" + element)
                       .build();
    }

    @Test(enabled = false)
    @Override
    @SuppressWarnings("checkstyle:LineLength")
    public void required_spec205_blackbox_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal()
            throws Exception {
        // not compliant
    }
}
