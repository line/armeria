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

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.EventExecutor;

final class ByteStreamMessageOutputStream implements ByteStreamMessage {

    private final Consumer<OutputStream> outputStreamWriter;
    private final Executor executor;

    private final StreamMessageAndWriter<HttpData> streamWriter = new DefaultStreamMessage<>();
    private final ByteStreamMessage delegate = ByteStreamMessage.of(streamWriter);
    private final OutputStream outputStream = new StreamWriterOutputStream(streamWriter);

    ByteStreamMessageOutputStream(Consumer<OutputStream> outputStreamWriter, Executor executor) {
        this.outputStreamWriter = outputStreamWriter;
        this.executor = executor;
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
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor eventExecutor,
                          SubscriptionOption... options) {
        executor.execute(() -> outputStreamWriter.accept(outputStream));
        delegate.subscribe(subscriber, eventExecutor, options);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(cause);
    }

    private static final class StreamWriterOutputStream extends OutputStream {

        private final StreamMessageAndWriter<HttpData> streamWriter;

        StreamWriterOutputStream(StreamMessageAndWriter<HttpData> streamWriter) {
            this.streamWriter = streamWriter;
        }

        @Override
        public void write(int b) throws IOException {
            final ByteBuf buf = Unpooled.buffer(1, 1).writeByte(b);
            if (!streamWriter.tryWrite(HttpData.wrap(buf))) {
                throw new IOException("Stream closed");
            }
            streamWriter.whenConsumed().join(); // Blocking wait until tryWrite is consumed
        }

        @Override
        public void close() throws IOException {
            streamWriter.close();
        }
    }
}
