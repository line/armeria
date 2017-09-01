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

import static com.linecorp.armeria.common.stream.DefaultStreamMessageVerification.createStreamMessage;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class DeferredStreamMessageVerification extends StreamMessageVerification<Long> {

    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        final DeferredStreamMessage<Long> stream = new DeferredStreamMessage<>();
        stream.delegate(createStreamMessage(elements, false));
        return stream;
    }

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        final DeferredStreamMessage<Long> stream = new DeferredStreamMessage<>();
        DefaultStreamMessage<Long> delegate = new DefaultStreamMessage<>();
        delegate.subscribe(new NoopSubscriber<>());
        stream.delegate(delegate);
        return stream;
    }

    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        final DeferredStreamMessage<Long> stream = new DeferredStreamMessage<>();
        if (elements == 0) {
            stream.abort();
        }

        stream.delegate(new StreamMessageWrapper<Long>(createStreamMessage(elements + 1, false)) {
            @Override
            public void subscribe(Subscriber<? super Long> subscriber, boolean withPooledObjects) {
                super.subscribe(new Subscriber<Long>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        subscriber.onSubscribe(s);
                    }

                    @Override
                    public void onNext(Long value) {
                        subscriber.onNext(value);
                        if (elements == value) {
                            stream.abort();
                        }
                    }

                    @Override
                    public void onError(Throwable cause) {
                        subscriber.onError(cause);
                    }

                    @Override
                    public void onComplete() {
                        subscriber.onComplete();
                    }
                }, withPooledObjects);
            }
        });

        return stream;
    }
}
