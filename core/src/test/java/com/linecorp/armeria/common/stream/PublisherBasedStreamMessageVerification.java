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

import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.internal.common.stream.NoopSubscription;

public class PublisherBasedStreamMessageVerification extends StreamMessageVerification<Long> {

    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        return new PublisherBasedStreamMessage<>(createStreamMessage(elements, false));
    }

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        final Publisher<Long> publisher = s -> { /* noop */ };
        final StreamMessage<Long> stream = new PublisherBasedStreamMessage<>(publisher);
        stream.subscribe(NoopSubscriber.get());
        return stream;
    }

    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        if (elements == 0) {
            final PublisherBasedStreamMessage<Long> stream =
                    new PublisherBasedStreamMessage<>(s -> s.onSubscribe(NoopSubscription.get()));
            stream.abort();
            return stream;
        }

        final AtomicReference<StreamMessage<Long>> stream = new AtomicReference<>();
        final Publisher<Long> publisher =
                new StreamMessageWrapper<Long>(createStreamMessage(elements + 1, false)) {

            @Override
            public void subscribe(Subscriber<? super Long> subscriber) {
                super.subscribe(new Subscriber<Long>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        subscriber.onSubscribe(s);
                        if (elements == 0) {
                            stream.get().abort();
                        }
                    }

                    @Override
                    public void onNext(Long value) {
                        subscriber.onNext(value);
                        if (value == elements) {
                            stream.get().abort();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        subscriber.onError(t);
                    }

                    @Override
                    public void onComplete() {
                        subscriber.onComplete();
                    }
                });
            }
        };

        stream.set(new PublisherBasedStreamMessage<>(publisher));
        return stream.get();
    }
}
