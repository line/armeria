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

package com.linecorp.armeria.common.stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.concurrent.EventExecutor;

final class PathStreamMessage implements ByteStreamMessage {

    private static final Logger logger = LoggerFactory.getLogger(PathStreamMessage.class);

    private static final AtomicIntegerFieldUpdater<PathStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(PathStreamMessage.class, "subscribed");

    private static final Set<StandardOpenOption> READ_OPERATION = ImmutableSet.of(StandardOpenOption.READ);

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    private final Path path;
    @Nullable
    private final ExecutorService blockingTaskExecutor;
    private final ByteBufAllocator alloc;
    private final int bufferSize;

    private long offset;
    private long length = Long.MAX_VALUE;

    private volatile int subscribed;

    @Nullable
    private volatile PathSubscription pathSubscription;

    PathStreamMessage(Path path, @Nullable ExecutorService blockingTaskExecutor, ByteBufAllocator alloc,
                      int bufferSize) {
        this.path = requireNonNull(path, "path");
        this.blockingTaskExecutor = blockingTaskExecutor;
        this.alloc = alloc;
        this.bufferSize = bufferSize;
    }

    @Override
    public ByteStreamMessage range(long offset, long length) {
        checkArgument(offset >= 0, "offset: %s (expected: >= 0)", offset);
        checkArgument(length > 0, "length: %s (expected: > 0)", length);
        checkState(subscribed == 0, "cannot specify range(%s, %s) after this %s is subscribed", offset, length,
                   PathStreamMessage.class);
        this.offset = offset;
        this.length = length;
        return this;
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
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("Only single subscriber is allowed!"));
            return;
        }

        if (executor.inEventLoop()) {
            subscribe0(subscriber, executor, options);
        } else {
            executor.execute(() -> subscribe0(subscriber, executor, options));
        }
    }

    private void subscribe0(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                            SubscriptionOption... options) {
        final ExecutorService blockingTaskExecutor;
        if (this.blockingTaskExecutor != null) {
            blockingTaskExecutor = this.blockingTaskExecutor;
        } else {
            final ServiceRequestContext serviceRequestContext = ServiceRequestContext.currentOrNull();
            if (serviceRequestContext != null) {
                blockingTaskExecutor = serviceRequestContext.blockingTaskExecutor();
            } else {
                blockingTaskExecutor = null;
            }
        }
        AsynchronousFileChannel fileChannel = null;
        boolean success = false;
        try {
            // The default thread pool is used if blockingTaskExecutor is null
            fileChannel = AsynchronousFileChannel.open(path, READ_OPERATION, blockingTaskExecutor);
            if (fileChannel.size() == 0) {
                subscriber.onSubscribe(NoopSubscription.get());

                if (completionFuture.isCompletedExceptionally()) {
                    completionFuture.handle((unused, cause) -> {
                        subscriber.onError(Exceptions.peel(cause));
                        return null;
                    });
                } else {
                    subscriber.onComplete();
                    completionFuture.complete(null);
                }
                return;
            }
            success = true;
        } catch (IOException e) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(e);
            completionFuture.completeExceptionally(e);
            return;
        } finally {
            if (!success && fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    logger.warn("Unexpected exception while closing {}.", fileChannel, e);
                }
            }
        }

        final int bufferSize = Math.min(Ints.saturatedCast(length), this.bufferSize);
        final PathSubscription pathSubscription =
                new PathSubscription(fileChannel, subscriber, executor, offset, length, bufferSize,
                                     containsNotifyCancellation(options), containsWithPooledObjects(options));
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
        private final EventExecutor executor;
        private final int bufferSize;
        private final long end;
        private final boolean notifyCancellation;
        private final boolean withPooledObjects;

        private boolean reading;
        private boolean closed;

        private volatile long requested;
        private volatile long position;

        private PathSubscription(AsynchronousFileChannel fileChannel, Subscriber<? super HttpData> downstream,
                                 EventExecutor executor, long offset, long length, int bufferSize,
                                 boolean notifyCancellation, boolean withPooledObjects) {
            this.fileChannel = fileChannel;
            this.downstream = downstream;
            this.executor = executor;
            this.bufferSize = bufferSize;
            end = LongMath.saturatedAdd(offset, length);

            this.notifyCancellation = notifyCancellation;
            this.withPooledObjects = withPooledObjects;
            position = offset;
        }

        @Override
        public void request(long n) {
            if (n <= 0L) {
                downstream.onError(
                        new IllegalArgumentException("Rule §3.9 violated: non-positive subscription requests " +
                                                     "are forbidden."));
                cancel();
            } else {
                request0(n);
            }
        }

        private void request0(long n) {
            final long requested = this.requested;
            if (requested == Long.MAX_VALUE) {
                return;
            }
            if (n == Long.MAX_VALUE) {
                this.requested = Long.MAX_VALUE;
            } else {
                this.requested = LongMath.saturatedAdd(requested, n);
            }

            if (requested > 0) {
                // PathSubscription is reading a file.
                // New requests will be handled by 'completed(Integer, ByteBuf)'.
                return;
            }

            read();
        }

        private void read() {
            if (!reading && !closed && requested > 0) {
                requested--;
                reading = true;
                final long position = this.position;
                final int bufferSize = Math.min(this.bufferSize, Ints.saturatedCast(end - position));
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
                    byteBuf.release();
                    maybeCloseFileChannel();
                } else {
                    if (result >= 0) {
                        position += result;
                        final HttpData data;
                        if (withPooledObjects) {
                            byteBuf.writerIndex(result);
                            data = HttpData.wrap(byteBuf);
                        } else {
                            data = HttpData.wrap(ByteBufUtil.getBytes(byteBuf, 0, result));
                            byteBuf.release();
                        }
                        downstream.onNext(data);
                        final long position = this.position;
                        assert position <= end;
                        if (position < end) {
                            reading = false;
                            read();
                        } else {
                            maybeCloseFileChannel();
                            close0(null);
                        }
                    } else {
                        byteBuf.release();
                        maybeCloseFileChannel();
                        close0(null);
                    }
                }
            });
        }

        @Override
        public void failed(Throwable ex, ByteBuf byteBuf) {
            executor.execute(() -> {
                byteBuf.release();
                maybeCloseFileChannel();
                close0(ex);
            });
        }

        private void maybeCloseFileChannel() {
            if (fileChannel.isOpen()) {
                try {
                    fileChannel.close();
                } catch (IOException cause) {
                    logger.warn("Unexpected exception while closing {}.", fileChannel, cause);
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
