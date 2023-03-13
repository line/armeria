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
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;

import io.netty.util.concurrent.EventExecutor;

final class ByteStreamMessageOutputStream implements ByteStreamMessage {

    private final StreamWriter<HttpData> outputStreamWriter = StreamMessage.streaming();
    private final ByteStreamMessage delegate = ByteStreamMessage.of(outputStreamWriter);

    private final Consumer<? super OutputStream> outputStreamConsumer;
    private final Executor blockingTaskExecutor;

    ByteStreamMessageOutputStream(Consumer<? super OutputStream> outputStreamConsumer,
                                  Executor blockingTaskExecutor) {
        requireNonNull(outputStreamConsumer, "outputStreamConsumer");
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        this.outputStreamConsumer = outputStreamConsumer;
        this.blockingTaskExecutor = blockingTaskExecutor;
    }

    @Override
    public ByteStreamMessage range(long offset, long length) {
        delegate.range(offset, length);
        return this;
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
    public long demand() {
        return delegate.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        final Subscriber<HttpData> outputStreamSubscriber = new OutputStreamSubscriber(
                subscriber, outputStreamWriter, outputStreamConsumer, blockingTaskExecutor);
        delegate.subscribe(outputStreamSubscriber, executor, options);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }

    private static final class OutputStreamSubscriber implements Subscriber<HttpData> {

        private final Subscriber<? super HttpData> downstream;
        private final StreamWriter<HttpData> outputStreamWriter;
        private final Consumer<? super OutputStream> outputStreamConsumer;
        private final Executor blockingTaskExecutor;

        OutputStreamSubscriber(Subscriber<? super HttpData> downstream,
                               StreamWriter<HttpData> outputStreamWriter,
                               Consumer<? super OutputStream> outputStreamConsumer,
                               Executor blockingTaskExecutor) {
            requireNonNull(downstream, "downstream");
            requireNonNull(outputStreamWriter, "outputStreamWriter");
            requireNonNull(outputStreamConsumer, "outputStreamConsumer");
            requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
            this.downstream = downstream;
            this.outputStreamWriter = outputStreamWriter;
            this.outputStreamConsumer = outputStreamConsumer;
            this.blockingTaskExecutor = blockingTaskExecutor;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            downstream.onSubscribe(subscription);
            blockingTaskExecutor.execute(() -> {
                try {
                    outputStreamConsumer.accept(new StreamWriterOutputStream(outputStreamWriter));
                } catch (Throwable t) {
                    outputStreamWriter.abort(t);
                }
            });
        }

        @Override
        public void onNext(HttpData data) {
            requireNonNull(data, "data");
            downstream.onNext(data);
        }

        @Override
        public void onError(Throwable t) {
            requireNonNull(t, "t");
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }

    private static final class StreamWriterOutputStream extends OutputStream {

        private final StreamWriter<HttpData> streamWriter;

        StreamWriterOutputStream(StreamWriter<HttpData> streamWriter) {
            this.streamWriter = streamWriter;
        }

        @Override
        public void write(int b) throws IOException {
            final HttpData data = HttpData.wrap(new byte[] { (byte) b });
            if (!streamWriter.tryWrite(data)) {
                throw new IOException("Stream closed");
            }
            streamWriter.whenConsumed().join();
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            final HttpData data = HttpData.copyOf(bytes, off, len);
            if (!streamWriter.tryWrite(data)) {
                throw new IOException("Stream closed");
            }
            streamWriter.whenConsumed().join();
        }

        @Override
        public void close() throws IOException {
            streamWriter.close();
        }
    }
}
