/*
 * Copyright 2020 LINE Corporation
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

import java.util.stream.LongStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.tck.SubscriberBlackboxVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage.AbortableSubscriber;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.ImmediateEventExecutor;
import reactor.core.publisher.Flux;

public class AbortableSubscriberBlackboxTckTest extends SubscriberBlackboxVerification<Object> {

    private PublisherBasedStreamMessage<Object> publisher;

    protected AbortableSubscriberBlackboxTckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public Publisher<Object> createHelperPublisher(long l) {
        final Flux<Long> upstream = Flux.fromStream(LongStream.rangeClosed(1, l).boxed());
        return publisher = new PublisherBasedStreamMessage<>(upstream);
    }

    @Override
    public Long createElement(int element) {
        return null;
    }

    @Override
    public Subscriber<Object> createSubscriber() {
        return new AbortableSubscriber(publisher, NoopSubscriber.get(), ImmediateEventExecutor.INSTANCE, false);
    }

    @Test(enabled = false)
    @Override
    @SuppressWarnings("checkstyle:LineLength")
    public void required_spec205_blackbox_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal()
            throws Exception {
        // not compliant
    }
}
