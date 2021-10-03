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

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.HttpDecoderInput;
import com.linecorp.armeria.common.stream.HttpDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.stream.DecodedHttpStreamMessage;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class MultipartDecoder implements StreamMessage<BodyPart>, HttpDecoder<BodyPart>, Subscriber<BodyPart> {

    private static final Logger logger = LoggerFactory.getLogger(MultipartDecoder.class);

    private final DecodedHttpStreamMessage<BodyPart> decoded;
    private final String boundary;

    @Nullable
    private MimeParser parser;
    @Nullable
    private Subscriber<? super BodyPart> subscriber;
    @Nullable
    private BodyPartPublisher bodyPartPublisher;
    @Nullable
    private Subscription subscription;
    // To track how many body part we need. Always keep DecodedHttpStreamMessage's demand less or equal than 1.
    private long demand;

    MultipartDecoder(StreamMessage<? extends HttpData> upstream, String boundary, ByteBufAllocator alloc) {
        decoded = new DecodedHttpStreamMessage<>(upstream, this, alloc);
        this.boundary = boundary;
    }

    @Override
    public void process(HttpDecoderInput in, HttpDecoderOutput<BodyPart> out) throws Exception {
        if (parser == null) {
            parser = new MimeParser(in, out, boundary, this);
        }
        parser.parse();
    }

    @Override
    public void processOnComplete(HttpDecoderOutput<BodyPart> out) {
        if (parser != null) {
            parser.close();
        }
    }

    @Override
    public void processOnError(Throwable cause) {
        if (parser != null) {
            try {
                parser.close();
            } catch (MimeParsingException ex) {
                logger.warn(ex.getMessage(), ex);
            }
        }
    }

    @Override
    public boolean isOpen() {
        return decoded.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return decoded.isEmpty();
    }

    @Override
    public long demand() {
        return decoded.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return decoded.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super BodyPart> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        this.subscriber = subscriber;
        decoded.subscribe(this, executor, options);
    }

    @Override
    public void abort() {
        decoded.abort();
    }

    @Override
    public void abort(Throwable cause) {
        decoded.abort(cause);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        assert subscriber != null;
        subscriber.onSubscribe(new Subscription() {
            @Override
            public void request(long n) {
                final long oldDemand = demand;
                demand += n;
                if (oldDemand == 0) {
                    // We want first body publisher
                    if (bodyPartPublisher == null) {
                        //This will trigger DecodedHttpStreamMessage's upstream.
                        subscription.request(1);
                    } else {
                        // Just wait bodyPart finish.
                    }
                }
            }

            @Override
            public void cancel() {
                subscription.cancel();
            }
        });
    }

    @Override
    public void onNext(BodyPart bodyPart) {
        assert subscriber != null;
        demand--;
        subscriber.onNext(bodyPart);
    }

    @Override
    public void onError(Throwable t) {
        assert subscriber != null;
        subscriber.onError(t);
    }

    @Override
    public void onComplete() {
        assert subscriber != null;
        subscriber.onComplete();
    }

    BodyPartPublisher onBodyPartBegin() {
        bodyPartPublisher = new BodyPartPublisher();
        return bodyPartPublisher;
    }

    void onBodyPartEnd() {
        bodyPartPublisher = null;
        if (demand > 0) {
            // BodyPart must be triggered after onSubscribe.
            assert subscription != null;
            // Trigger next body part
            subscription.request(1);
        }
    }

    /**
     * Subscriber may request after parser handle HttpData completely.
     * So we need to re-trigger the upstream if there is no upstream request anymore.
     */
    class BodyPartPublisher extends DefaultStreamMessage<HttpData> {

        @Override
        protected void onRequest(long n) {
            // old demand == 0, the first request of http data.
            if (demand() == 0) {
                pullMoreData();
            }
        }

        private void pullMoreData() {
            decoded.askUpstreamForElement();
            whenConsumed()
                    .thenRun(() -> {
                        // Check if it's ended, we can't request more if body part is finished
                        if (bodyPartPublisher != null && demand() > 0) {
                            pullMoreData();
                        }
                    });
        }

    }
}
