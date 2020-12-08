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

package com.linecorp.armeria.common.multipart;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.HttpDeframerHandler;
import com.linecorp.armeria.common.stream.HttpDeframerInput;
import com.linecorp.armeria.common.stream.HttpDeframerOutput;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.CompositeException;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

class MultipartDecoder implements HttpDeframer<BodyPart>, HttpDeframerHandler<BodyPart> {

    private final HttpDeframer<BodyPart> delegate;
    private final String boundary;

    @Nullable
    private MimeParser parser;

    MultipartDecoder(String boundary, ByteBufAllocator alloc) {
        delegate = HttpDeframer.of(this, alloc);
        this.boundary = boundary;
    }

    @Override
    public void process(HttpDeframerInput in, HttpDeframerOutput<BodyPart> out) throws Exception {
        if (parser == null) {
            parser = new MimeParser(in, out, boundary);
        }
        parser.parse();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super BodyPart> subscriber, EventExecutor executor) {
        delegate.subscribe(subscriber, executor);
    }

    @Override
    public void subscribe(Subscriber<? super BodyPart> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(subscriber, executor, options);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }

    @Override
    public void onSubscribe(Subscription s) {
        delegate.onSubscribe(s);
    }

    @Override
    public void onNext(HttpObject httpObject) {
        delegate.onNext(httpObject);
    }

    @Override
    public void onError(Throwable t) {
        if (parser != null) {
            try {
                parser.close();
            } catch (MimeParsingException ex) {
                delegate.onError(new CompositeException(ex, t));
                return;
            }
        }
        delegate.onError(t);
    }

    @Override
    public void onComplete() {
        if (parser != null) {
            try {
                parser.close();
            } catch (MimeParsingException ex) {
                delegate.onError(ex);
                return;
            }
        }
        delegate.onComplete();
    }
}
