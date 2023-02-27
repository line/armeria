/*
 * Copyright 2023 LINE Corporation
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
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.concurrent.EventExecutor;

final class InputStreamStreamMessage implements ByteStreamMessage {

    private static final Logger logger = LoggerFactory.getLogger(InputStreamStreamMessage.class);

    private static final AtomicIntegerFieldUpdater<InputStreamStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(InputStreamStreamMessage.class, "subscribed");

    static final int DEFAULT_BUFFER_SIZE = 8192;

    private final InputStream inputStream;
    @Nullable
    private final ExecutorService blockingTaskExecutor;
    private final int bufferSize;

    private long offset;
    private long length = Long.MAX_VALUE;

    private volatile int subscribed;
    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Nullable
    private volatile InputStreamSubscription inputStreamSubscription;

    InputStreamStreamMessage(InputStream inputStream, @Nullable ExecutorService blockingTaskExecutor,
                             int bufferSize) {
        requireNonNull(inputStream, "inputStream");
        this.inputStream = inputStream;
        this.blockingTaskExecutor = blockingTaskExecutor;
        this.bufferSize = bufferSize;
    }

    @Override
    public ByteStreamMessage range(long offset, long length) {
        checkArgument(offset >= 0, "offset: %s (expected: >= 0)", offset);
        checkArgument(length > 0, "length: %s (expected: > 0)", length);
        checkState(subscribed == 0, "cannot specify range(%s, %s) after this %s is subscribed",
                   offset, length, InputStreamStreamMessage.class);
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
        final InputStreamSubscription inputStreamSubscription = this.inputStreamSubscription;
        return inputStreamSubscription == null || inputStreamSubscription.position == 0;
    }

    @Override
    public long demand() {
        final InputStreamSubscription inputStreamSubscription = this.inputStreamSubscription;
        if (inputStreamSubscription != null) {
            return inputStreamSubscription.requested;
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
                blockingTaskExecutor = CommonPools.blockingTaskExecutor();
            }
        }

        final InputStreamSubscription inputStreamSubscription = new InputStreamSubscription(
                subscriber, executor, blockingTaskExecutor, bufferSize, offset, length,
                containsNotifyCancellation(options));
        this.inputStreamSubscription = inputStreamSubscription;
        subscriber.onSubscribe(inputStreamSubscription);

        if (completionFuture.isCompletedExceptionally()) {
            completionFuture.whenComplete((unused, cause) -> {
                if (cause != null) {
                    abort(cause);
                } else {
                    abort();
                }
            });
        }
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");

        // `completionFuture` should be set before `inputStreamSubscription` is read
        // to guarantee the visibility of the abortion `cause` after
        // inputStreamSubscription is set in `subscriber0()`.
        completionFuture.completeExceptionally(cause);

        final InputStreamSubscription inputStreamSubscription = this.inputStreamSubscription;
        if (inputStreamSubscription != null) {
            inputStreamSubscription.close(cause);
        }
    }

    private final class InputStreamSubscription implements Subscription {

        private Subscriber<? super HttpData> downstream;
        private final EventExecutor executor;
        private final ExecutorService blockingTaskExecutor;
        private final int bufferSize;

        private final long offset;
        private final long end;
        private volatile long position;

        private final boolean notifyCancellation;

        private boolean closed;
        private volatile long requested;

        private InputStreamSubscription(Subscriber<? super HttpData> downstream, EventExecutor executor,
                                        ExecutorService blockingTaskExecutor, int bufferSize,
                                        long offset, long length, boolean notifyCancellation) {
            requireNonNull(downstream, "downstream");
            requireNonNull(executor, "executor");
            requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
            this.downstream = downstream;
            this.executor = executor;
            this.blockingTaskExecutor = blockingTaskExecutor;
            this.bufferSize = bufferSize;
            this.offset = offset;
            end = LongMath.saturatedAdd(offset, length);
            this.notifyCancellation = notifyCancellation;
        }

        @Override
        public void request(long n) {
            if (n <= 0L) {
                close(new IllegalArgumentException(
                        "Rule §3.9 violated: non-positive subscription requests are forbidden."));
                return;
            }

            if (closed) {
                return;
            }

            if (executor.inEventLoop()) {
                request0(n);
            } else {
                executor.execute(() -> request0(n));
            }
        }

        private void request0(long n) {
            final long oldRequested = requested;
            if (oldRequested == Long.MAX_VALUE) {
                return;
            }
            if (n == Long.MAX_VALUE) {
                requested = Long.MAX_VALUE;
            } else {
                requested = LongMath.saturatedAdd(oldRequested, n);
            }

            if (oldRequested > 0) {
                // InputStreamSubscription is reading input stream.
                // New requests will be handled by 'publishDownstream(HttpData)'.
                return;
            }

            readBytes();
        }

        private void readBytes() {
            if (requested <= 0) {
                return;
            }

            blockingTaskExecutor.execute(() -> {
                if (position >= end) {
                    close(null);
                    return;
                }

                if (position < offset) {
                    final long skip = offset - position;
                    final long actualSkipped;
                    try {
                        actualSkipped = inputStream.skip(skip);
                    } catch (IOException e) {
                        close(e);
                        return;
                    }

                    if (actualSkipped < skip) {
                        close(null);
                        return;
                    }

                    position += skip;
                }

                final int bufferSize = Math.min(this.bufferSize, Ints.saturatedCast(end - position));
                final int len;
                final byte[] readBytes = new byte[bufferSize];
                try {
                    len = inputStream.read(readBytes);
                } catch (IOException e) {
                    close(e);
                    return;
                }

                if (len == -1) {
                    close(null);
                    return;
                }

                final HttpData data = HttpData.wrap(readBytes, 0, len);
                position += len;

                executor.execute(() -> publishDownstream(data));
            });
        }

        private void publishDownstream(HttpData data) {
            if (closed) {
                return;
            }

            downstream.onNext(data);
            requested--;
            readBytes();
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

            final CancelledSubscriptionException cause = CancelledSubscriptionException.get();
            if (notifyCancellation) {
                downstream.onError(cause);
            }
            downstream = NoopSubscriber.get();
            completionFuture.completeExceptionally(cause);
            maybeCloseInputStream();
        }

        private void maybeCloseInputStream() {
            try {
                inputStream.close();
            } catch (IOException e) {
                logger.warn("Unexpected exception while closing input stream {}.", inputStream, e);
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
            if (closed) {
                return;
            }
            closed = true;

            if (cause == null) {
                downstream.onComplete();
                completionFuture.complete(null);
            } else {
                downstream.onError(cause);
                completionFuture.completeExceptionally(cause);
            }
            maybeCloseInputStream();
        }
    }
}
