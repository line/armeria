/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *         https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;

/**
 * Asynchronously writes a {@code StreamMessage<HttpMessage>} to a {@link File}.
 */
final class AsyncFileWriter implements Subscriber<HttpData>,
                                       CompletionHandler<Integer, Entry<ByteBuffer, ByteBuf>> {

    private static final Logger logger = LoggerFactory.getLogger(AsyncFileWriter.class);

    private final CompletableFuture<Path> completionFuture = new CompletableFuture<>();
    private final Path path;
    private final EventExecutor eventExecutor;
    private final Set<OpenOption> options;
    private final ExecutorService blockingTaskExecutor;
    private final StreamMessage<? extends HttpData> publisher;

    @Nullable
    private AsynchronousFileChannel fileChannel;
    @Nullable
    private Subscription subscription;

    // state
    private long position;
    private boolean writing;
    private boolean closing;

    AsyncFileWriter(StreamMessage<? extends HttpData> publisher, Path path, Set<OpenOption> options,
                    EventExecutor eventExecutor, ExecutorService blockingTaskExecutor) {
        this.path = path;
        this.eventExecutor = eventExecutor;
        this.options = options;
        this.blockingTaskExecutor = blockingTaskExecutor;
        this.publisher = publisher;
        publisher.subscribe(this, eventExecutor, SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        this.subscription = subscription;

        try {
            fileChannel = AsynchronousFileChannel.open(path, options, blockingTaskExecutor);
        } catch (IOException e) {
            maybeCloseFileChannel(e);
            return;
        }

        subscription.request(1);
    }

    @Override
    public void onNext(HttpData httpData) {
        if (httpData.isEmpty()) {
            httpData.close();
            subscription.request(1);
        } else {
            final ByteBuf byteBuf = httpData.byteBuf();
            final ByteBuffer byteBuffer = byteBuf.nioBuffer();
            writing = true;
            fileChannel.write(byteBuffer, position, Maps.immutableEntry(byteBuffer, byteBuf), this);
        }
    }

    @Override
    public void onError(Throwable t) {
        maybeCloseFileChannel(t);
    }

    @Override
    public void onComplete() {
        if (!writing) {
            maybeCloseFileChannel(null);
        } else {
            closing = true;
        }
    }

    public CompletableFuture<Path> whenComplete() {
        return completionFuture;
    }

    @Override
    public void completed(Integer result, Entry<ByteBuffer, ByteBuf> attachment) {
        assert subscription != null;
        eventExecutor.execute(() -> {
            final ByteBuf byteBuf = attachment.getValue();
            if (result > -1) {
                position += result;
                final ByteBuffer byteBuffer = attachment.getKey();
                if (byteBuffer.hasRemaining()) {
                    fileChannel.write(byteBuffer, position, attachment, this);
                } else {
                    byteBuf.release();
                    writing = false;

                    if (closing) {
                        maybeCloseFileChannel(null);
                    } else {
                        subscription.request(1);
                    }
                }
            } else {
                byteBuf.release();
                subscription.cancel();
                maybeCloseFileChannel(new IOException(
                        "Unexpected exception while writing data to '" + path + "' + : result " + result));
            }
        });
    }

    @Override
    public void failed(Throwable cause, Entry<ByteBuffer, ByteBuf> attachment) {
        assert subscription != null;
        subscription.cancel();
        attachment.getValue().release();
        maybeCloseFileChannel(cause);
    }

    private void maybeCloseFileChannel(@Nullable Throwable cause) {
        if (completionFuture.isDone()) {
            return;
        }

        if (cause == null) {
            completionFuture.complete(path);
        } else {
            publisher.abort(cause);
            completionFuture.completeExceptionally(cause);
        }

        if (fileChannel != null && fileChannel.isOpen()) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                logger.warn("Failed to close '" + path + '\'', e);
            }
        }
    }
}
