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

package com.linecorp.armeria.common.stream;

import static com.linecorp.armeria.common.stream.StreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;

final class PathStreamMessage implements StreamMessage<HttpData> {

    static final int DEFAULT_FILE_BUFFER_SIZE = 4096;

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    private final Path path;
    private final ByteBufAllocator alloc;
    private final int bufferSize;

    private boolean subscribed;

    @Nullable
    private volatile PathSubscription pathSubscription;

    PathStreamMessage(Path path, ByteBufAllocator alloc, int bufferSize) {
        this.path = requireNonNull(path, "path");
        this.alloc = requireNonNull(alloc, "alloc");
        this.bufferSize = bufferSize;
    }

    @Override
    public boolean isOpen() {
        return !completionFuture.isDone();
    }

    @Override
    public boolean isEmpty() {
        if (isOpen()) {
            return false;
        }
        final PathSubscription pathSubscription = this.pathSubscription;
        return pathSubscription == null || pathSubscription.position == 0;
    }

    @Override
    public long demand() {
        final PathSubscription pathSubscription = this.pathSubscription;
        if (pathSubscription != null) {
            return pathSubscription.requested;
        } else {
            return 0;
        }
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, EMPTY_OPTIONS);
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        if (executor.inEventLoop()) {
            subscribe0(subscriber, executor, options);
        } else {
            executor.execute(() -> subscribe0(subscriber, executor, options));
        }
    }

    private void subscribe0(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                            SubscriptionOption... options) {
        if (subscribed) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("Only single subscriber is allowed!"));
            return;
        }
        subscribed = true;

        final AsynchronousFileChannel fileChannel;
        try {
            fileChannel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
            if (fileChannel.size() == 0) {
                subscriber.onSubscribe(NoopSubscription.get());

                if (completionFuture.isCompletedExceptionally()) {
                    try {
                        completionFuture.get();
                    } catch (Exception ex) {
                       subscriber.onError(Exceptions.peel(ex));
                    }
                } else {
                    subscriber.onComplete();
                    completionFuture.complete(null);
                }
                return;
            }
        } catch (IOException e) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(e);
            completionFuture.completeExceptionally(e);
            return;
        }

        final PathSubscription pathSubscription =
                new PathSubscription(fileChannel, subscriber, executor,
                                     bufferSize, containsNotifyCancellation(options));
        this.pathSubscription = pathSubscription;
        subscriber.onSubscribe(pathSubscription);
    }


    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");

        final PathSubscription pathSubscription = this.pathSubscription;
        if (pathSubscription != null) {
            pathSubscription.maybeCloseFileChannel();
            pathSubscription.close(cause);
        }
        completionFuture.completeExceptionally(cause);
    }

    private final class PathSubscription implements Subscription, CompletionHandler<Integer, ByteBuf> {

        private final AsynchronousFileChannel fileChannel;
        private Subscriber<? super HttpData> downstream;
        private final int bufferSize;
        private final EventExecutor executor;
        private final boolean notifyCancellation;

        private boolean reading;
        private boolean closed;

        private volatile long requested;
        private volatile int position;

        private PathSubscription(AsynchronousFileChannel fileChannel, Subscriber<? super HttpData> downstream,
                                 EventExecutor executor, int bufferSize, boolean notifyCancellation) {
            this.fileChannel = fileChannel;
            this.downstream = downstream;
            this.executor = executor;
            this.bufferSize = bufferSize;
            this.notifyCancellation = notifyCancellation;
        }

        @Override
        public void request(long n) {
            if (n <= 0L) {
                cancel();
                downstream.onError(
                        new IllegalArgumentException("Rule §3.9 violated: non-positive subscription requests " +
                                                     "are forbidden."));
            } else {
                request0(n);
            }
        }

        private void request0(long n) {
            if (requested == Long.MAX_VALUE) {
                return;
            }
            if (n == Long.MAX_VALUE) {
                requested = Long.MAX_VALUE;
            }
            requested = LongMath.saturatedAdd(requested, n);
            read();
        }

        private void read() {
            if (requested > 0 && !closed && !reading) {
                requested--;
                reading = true;
                final ByteBuf buffer = alloc.buffer(bufferSize);
                fileChannel.read(buffer.nioBuffer(0, bufferSize), position, buffer, this);
            }
        }

        @Override
        public void cancel() {
            if (executor.inEventLoop()) {
                cancel0();
            } else {
                executor.execute(this::cancel0);
            }
        }

        private void cancel0() {
            if (closed) {
                return;
            }
            closed = true;

            if (!reading) {
                maybeCloseFileChannel();
            }

            final CancelledSubscriptionException cause = CancelledSubscriptionException.get();
            if (notifyCancellation) {
                downstream.onError(cause);
            }
            completionFuture.completeExceptionally(cause);
            downstream = NoopSubscriber.get();
        }

        @Override
        public void completed(Integer result, ByteBuf byteBuf) {
            executor.execute(() -> {
                if (closed) {
                    ReferenceCountUtil.release(byteBuf);
                    maybeCloseFileChannel();
                } else {
                    if (result > -1) {
                        position += result;
                        byteBuf.writerIndex(result);
                        downstream.onNext(HttpData.wrap(byteBuf));
                        reading = false;
                        read();
                    } else {
                        maybeCloseFileChannel();
                        close0(null);
                    }
                }
            });
        }

        @Override
        public void failed(Throwable ex, ByteBuf byteBuf) {
            executor.execute(() -> {
                ReferenceCountUtil.release(byteBuf);
                maybeCloseFileChannel();
                close0(ex);
            });
        }

        private void maybeCloseFileChannel() {
            if (fileChannel.isOpen()) {
                try {
                    fileChannel.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void close(@Nullable Throwable cause) {
            if (executor.inEventLoop()) {
                close0(cause);
            } else {
                executor.execute(() -> close0(cause));
            }
        }

        private void close0(@Nullable Throwable cause) {
            if (!closed) {
                closed = true;
                if (cause == null) {
                    downstream.onComplete();
                    completionFuture.complete(null);
                } else {
                    downstream.onError(cause);
                    completionFuture.completeExceptionally(cause);
                }
            }
        }
    }
}
