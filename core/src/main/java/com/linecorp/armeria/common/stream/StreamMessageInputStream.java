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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil;

final class StreamMessageInputStream<T> extends InputStream {

    static <T> StreamMessageInputStream<T> of(
            StreamMessage<T> source, Function<? super T, ? extends HttpData> httpDataConverter) {
        return new StreamMessageInputStream<>(source, httpDataConverter);
    }

    private final StreamMessage<T> source;
    private final StreamMessageInputStreamSubscriber<T> subscriber;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    @Nullable
    private volatile InputStream inputStream;
    private volatile boolean closed;

    private StreamMessageInputStream(StreamMessage<T> source,
                                     Function<? super T, ? extends HttpData> httpDataConverter) {
        requireNonNull(source, "source");
        requireNonNull(httpDataConverter, "httpDataConverter");
        this.source = source;
        subscriber = new StreamMessageInputStreamSubscriber<>(httpDataConverter);
    }

    @Override
    public int read() throws IOException {
        return read(in -> {
            try {
                return in.read();
            } catch (IOException e) {
                return -1;
            }
        });
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return read(in -> {
            try {
                return in.read(b, off, len);
            } catch (IOException e) {
                return -1;
            }
        });
    }

    private int read(Function<InputStream, Integer> function) throws IOException {
        checkClosed();
        if (subscribed.compareAndSet(false, true)) {
            source.subscribe(subscriber);
        }
        if (inputStream == null || inputStream.available() == 0) {
            try {
                inputStream = subscriber.nextStream();
            } catch (InterruptedException e) {
                return -1;
            }
        }
        return function.apply(inputStream);
    }

    @Override
    public void close() throws IOException {
        checkClosed();
        closed = true;
        subscriber.cancel();
    }

    @Override
    public int available() throws IOException {
        checkClosed();
        if (inputStream == null) {
            return 0;
        }
        return inputStream.available();
    }

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    private static final class StreamMessageInputStreamSubscriber<T> implements Subscriber<T> {

        private static final InputStream EMPTY_STREAM = new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };

        private final Function<? super T, ? extends HttpData> httpDataConverter;
        private final CompletableFuture<Subscription> upstream = new CompletableFuture<>();
        private final BlockingQueue<InputStream> queue = new LinkedBlockingDeque<>();
        private volatile boolean closed;

        StreamMessageInputStreamSubscriber(Function<? super T, ? extends HttpData> httpDataConverter) {
            requireNonNull(httpDataConverter, "httpDataConverter");
            this.httpDataConverter = httpDataConverter;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            upstream.complete(subscription);
        }

        @Override
        public void onNext(T item) {
            requireNonNull(item, "item");
            if (closed) {
                 StreamMessageUtil.closeOrAbort(item);
                 return;
            }
            try {
                queue.add(httpDataConverter.apply(item).toInputStream());
            } catch (Throwable ex) {
                StreamMessageUtil.closeOrAbort(item, ex);
                upstream.join().cancel();
                onError(ex);
            }
        }

        @Override
        public void onError(Throwable cause) {
            closed = true;
            queue.add(EMPTY_STREAM); // to wake the BlockingQueue
        }

        @Override
        public void onComplete() {
            closed = true;
            queue.add(EMPTY_STREAM); // to wake the BlockingQueue
        }

        public InputStream nextStream() throws InterruptedException {
            if (closed) {
                return EMPTY_STREAM;
            }
            if (queue.isEmpty()) {
                upstream.join().request(1);
            }
            return queue.take();
        }

        public void cancel() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            upstream.join().cancel();
        }
    }
}
