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

import static com.linecorp.armeria.common.stream.StreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsWithPooledObjects;
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

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.concurrent.EventExecutor;

final class PathStreamMessage implements StreamMessage<HttpData> {

    private static final Logger logger = LoggerFactory.getLogger(PathStreamMessage.class);

    private static final AtomicIntegerFieldUpdater<PathStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(PathStreamMessage.class, "subscribed");

    static final int DEFAULT_FILE_BUFFER_SIZE = 8192;

    private static final Set<StandardOpenOption> READ_OPERATION = ImmutableSet.of(StandardOpenOption.READ);

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    private final Path path;
    private final ByteBufAllocator alloc;
    @Nullable
    private final ExecutorService blockingTaskExecutor;
    private final int bufferSize;

    private volatile int subscribed;

    @Nullable
    private volatile PathSubscription pathSubscription;

    PathStreamMessage(Path path, ByteBufAllocator alloc,
                      @Nullable ExecutorService blockingTaskExecutor, int bufferSize) {
        this.path = requireNonNull(path, "path");
        this.alloc = requireNonNull(alloc, "alloc");
        this.blockingTaskExecutor = blockingTaskExecutor;
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
            blockingTaskExecutor =
                    ServiceRequestContext.mapCurrent(ServiceRequestContext::blockingTaskExecutor, null);
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

        final PathSubscription pathSubscription =
                new PathSubscription(fileChannel, subscriber, executor,
                                     bufferSize, containsNotifyCancellation(options),
                                     containsWithPooledObjects(options));
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
        private final boolean withPooledObjects;

        private boolean reading;
        private boolean closed;

        private volatile long requested;
        private volatile int position;

        private PathSubscription(AsynchronousFileChannel fileChannel, Subscriber<? super HttpData> downstream,
                                 EventExecutor executor, int bufferSize, boolean notifyCancellation,
                                 boolean withPooledObjects) {
            this.fileChannel = fileChannel;
            this.downstream = downstream;
            this.executor = executor;
            this.bufferSize = bufferSize;
            this.notifyCancellation = notifyCancellation;
            this.withPooledObjects = withPooledObjects;
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
                        reading = false;
                        read();
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
