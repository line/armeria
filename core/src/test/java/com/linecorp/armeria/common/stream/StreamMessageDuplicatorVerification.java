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
import org.testng.SkipException;

public class StreamMessageDuplicatorVerification extends StreamMessageVerification<Long> {

    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        final StreamMessage<Long> source = createStreamMessage(elements, false);
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(source);
        return duplicator.duplicateStream();
    }

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        final StreamMessage<Long> source = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(source);
        final StreamMessage<Long> duplicate = duplicator.duplicateStream();
        duplicate.subscribe(new NoopSubscriber<>());
        return duplicate;
    }

    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        final StreamMessage<Long> source = createStreamMessage(elements + 1, false);
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(source);
        final StreamMessage<Long> duplicate = duplicator.duplicateStream();
        if (elements == 0) {
            duplicate.abort();
            return duplicate;
        }

        return new StreamMessageWrapper<Long>(duplicate) {
            @Override
            public void subscribe(Subscriber<? super Long> subscriber) {
                super.subscribe(new Subscriber<Long>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        subscriber.onSubscribe(s);
                    }

                    @Override
                    public void onNext(Long value) {
                        subscriber.onNext(value);
                        if (value == elements) {
                            duplicate.abort();
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
                });
            }
        };
    }

    @Override
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() {
        throw new SkipException(
                "Can't test because duplicator cannot cancel the upstream subscription " +
                "even if a downstream subscription has been cancelled. Duplicator will " +
                "end up raising OOME because it has to keep all published signals.");
    }

    private static class StreamMessageDuplicator
            extends AbstractStreamMessageDuplicator<Long, StreamMessage<Long>> {
        StreamMessageDuplicator(StreamMessage<Long> publisher) {
            super(publisher, l -> 8, 0);
        }

        @Override
        public StreamMessage<Long> doDuplicateStream(StreamMessage<Long> delegate) {
            return new MulticastStream(delegate);
        }

        private static class MulticastStream extends StreamMessageWrapper<Long> {
            MulticastStream(StreamMessage<Long> delegate) {
                super(delegate);
            }
        }
    }
}
