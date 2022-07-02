/*
 * Copyright 2022 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.stream.ByteBufsInputStream;
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil;

import io.netty.util.concurrent.EventExecutor;

final class StreamMessageInputStream<T> extends InputStream {

    private final StreamMessage<T> source;
    private final EventExecutor executor;
    private final StreamMessageInputStreamSubscriber<T> subscriber;
    private boolean subscribed;
    private boolean closed;

    StreamMessageInputStream(StreamMessage<T> source,
                             Function<? super T, ? extends HttpData> httpDataConverter,
                             EventExecutor executor) {
        requireNonNull(source, "source");
        requireNonNull(httpDataConverter, "httpDataConverter");
        requireNonNull(executor, "executor");
        this.source = source;
        this.executor = executor;
        subscriber = new StreamMessageInputStreamSubscriber<>(httpDataConverter);
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        ensureSubscribed();
        maybeRequest();
        return byteBufsInputStream().read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        ensureSubscribed();
        maybeRequest();
        return byteBufsInputStream().read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        source.abort();
        byteBufsInputStream().close();
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
        return byteBufsInputStream().available();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    private void ensureSubscribed() {
        if (!subscribed) {
            subscribed = true;
            source.subscribe(subscriber, executor);
            subscriber.whenSubscribed.join();
        }
    }

    private void maybeRequest() throws IOException {
        if (available() == 0) {
            subscriber.request();
        }
    }

    private ByteBufsInputStream byteBufsInputStream() {
        return subscriber.byteBufsInputStream;
    }

    private static final class StreamMessageInputStreamSubscriber<T> implements Subscriber<T> {

        private final Function<? super T, ? extends HttpData> httpDataConverter;
        private final CompletableFuture<Void> whenSubscribed = new CompletableFuture<>();
        private final ByteBufsInputStream byteBufsInputStream = new ByteBufsInputStream();
        @Nullable
        private volatile Subscription upstream;

        StreamMessageInputStreamSubscriber(Function<? super T, ? extends HttpData> httpDataConverter) {
            requireNonNull(httpDataConverter, "httpDataConverter");
            this.httpDataConverter = httpDataConverter;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            upstream = subscription;
            whenSubscribed.complete(null);
        }

        @Override
        public void onNext(T item) {
            requireNonNull(item, "item");
            if (byteBufsInputStream.isEos()) {
                 StreamMessageUtil.closeOrAbort(item);
                 return;
            }
            try {
                final HttpData result = httpDataConverter.apply(item);
                if (result.isEmpty()) {
                    result.close();
                    request();
                    return;
                }
                byteBufsInputStream.add(result.byteBuf());
            } catch (Throwable ex) {
                StreamMessageUtil.closeOrAbort(item, ex);
                upstream.cancel();
                onError(ex);
            }
        }

        @Override
        public void onError(Throwable cause) {
            byteBufsInputStream.setEos();
        }

        @Override
        public void onComplete() {
            byteBufsInputStream.setEos();
        }

        public void request() {
            if (byteBufsInputStream.isEos()) {
                return;
            }
            upstream.request(1);
        }
    }
}
