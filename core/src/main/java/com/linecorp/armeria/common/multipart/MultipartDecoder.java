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

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.HttpDecoderInput;
import com.linecorp.armeria.common.stream.HttpDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.stream.DecodedHttpStreamMessage;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class MultipartDecoder implements StreamMessage<BodyPart>, HttpDecoder<BodyPart> {

    private static final Logger logger = LoggerFactory.getLogger(MultipartDecoder.class);

    private final StreamMessage<BodyPart> decoded;
    private final String boundary;

    @Nullable
    private MimeParser parser;

    MultipartDecoder(StreamMessage<? extends HttpData> upstream, String boundary, ByteBufAllocator alloc) {
        decoded = new DecodedHttpStreamMessage<>(upstream, this, alloc);
        this.boundary = boundary;
    }

    @Override
    public void process(HttpDecoderInput in, HttpDecoderOutput<BodyPart> out) throws Exception {
        if (parser == null) {
            parser = new MimeParser(in, out, boundary);
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
    public void subscribe(Subscriber<? super BodyPart> subscriber, EventExecutor executor) {
        decoded.subscribe(subscriber, executor);
    }

    @Override
    public void subscribe(Subscriber<? super BodyPart> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        decoded.subscribe(subscriber, executor, options);
    }

    @Override
    public void abort() {
        decoded.abort();
    }

    @Override
    public void abort(Throwable cause) {
        decoded.abort(cause);
    }
}
