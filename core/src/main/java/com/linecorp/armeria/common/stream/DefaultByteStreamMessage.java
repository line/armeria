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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.IntMath;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;

final class DefaultByteStreamMessage implements ByteStreamMessage {

    private final StreamMessage<? extends HttpData> delegate;

    private int offset;
    private int length = -1;
    private long demand;

    @Nullable
    private volatile Throwable abortedCause;
    private volatile boolean subscribed;

    DefaultByteStreamMessage(StreamMessage<? extends HttpData> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ByteStreamMessage range(int offset, int length) {
        checkArgument(offset >= 0, "offset: %s (expected: >= 0)", offset);
        checkArgument(length > 0, "length: %s (expected: > 0)", length);
        checkState(!subscribed, "cannot specify range(%s, %s) after this %s is subscribed", offset, length,
                   DefaultByteStreamMessage.class);
        this.offset = offset;
        this.length = length;
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
        if (needsFiltering()) {
            return demand;
        } else {
            return delegate.demand();
        }
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        subscribed = true;
        if (needsFiltering()) {
            delegate.subscribe(
                    new FilteringSubscriber(subscriber, executor, options, offset, length),
                    executor, options);
        } else {
            delegate.subscribe(subscriber, executor, options);
        }
    }

    private boolean needsFiltering() {
        return offset != 0 || length != -1;
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        if (abortedCause != null) {
            return;
        }
        abortedCause = cause;
        delegate.abort(cause);
    }

    private final class FilteringSubscriber implements Subscriber<HttpData>, Subscription {

        private final Subscriber<? super HttpData> downstream;
        private final int offset;
        private final int end;
        private final EventExecutor executor;
        private final boolean notifyCancellation;

        @Nullable
        private Subscription upstream;
        @Nullable
        private HttpData buffer;
        private int position;
        private boolean inOnNext;
        private boolean completed;
        private boolean completing;

        FilteringSubscriber(Subscriber<? super HttpData> downstream,
                            EventExecutor executor, SubscriptionOption[] options, int offset, int length) {
            this.downstream = downstream;
            this.executor = executor;
            notifyCancellation = containsNotifyCancellation(options);
            this.offset = offset;
            if (length == -1) {
                end = Integer.MAX_VALUE;
            } else {
                end = IntMath.saturatedAdd(offset, length);
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            requireNonNull(s, "s");
            upstream = s;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(HttpData data) {
            requireNonNull(data, "data");
            assert buffer == null;
            if (completed) {
                data.close();
                return;
            }

            inOnNext = true;
            try {
                if (!onNext0(data)) {
                    return;
                }

                // Use a while-loop to avoid deep recursive calls.
                // https://github.com/reactive-streams/reactive-streams-jvm#3.3
                while (buffer != null && demand > 0) {
                    final HttpData buffer = this.buffer;
                    this.buffer = null;
                    if (!onNext0(buffer)) {
                        break;
                    }
                }

                if (completing && buffer == null) {
                    downstream.onComplete();
                    return;
                }

                if (buffer == null && demand > 0) {
                    requestOneOrCancel();
                }
            } finally {
                inOnNext = false;
            }
        }

        /**
         * Publishes the {@link HttpData} to the downstream.
         *
         * @return true if data is published to the downstream.
         */
        private boolean onNext0(HttpData data) {
            if (position >= end) {
                // Drop tail bytes. Fully received the desired data already.
                data.close();
                upstream.cancel();
                return false;
            }

            final int dataSize = data.length();
            final int dataEnd = IntMath.saturatedAdd(position, dataSize);
            final int skipBytes = Math.max(0, IntMath.saturatedSubtract(offset, position));
            final int dropBytes = Math.max(0, IntMath.saturatedSubtract(dataEnd, end));

            if (skipBytes >= dataSize) {
                // Skip the entire data and request the next element.
                data.close();
                position += dataSize;
                requestOneOrCancel();
                return false;
            }

            if (skipBytes == 0 && dropBytes == 0) {
                // Use the data as is.
                position += dataSize;
                downstream.onNext(data);
            } else {
                try {
                    final int slicedDataSize = dataSize - skipBytes - dropBytes;
                    final HttpData slicedData = retainedSlice(data, skipBytes, slicedDataSize);
                    final int nextOffset = skipBytes + slicedDataSize;

                    if (dropBytes == 0) {
                        final int remainingLength = dataSize - nextOffset;
                        if (remainingLength > 0) {
                            // The stored buffer will be passed down later when there's demand.
                            buffer = retainedSlice(data, nextOffset, remainingLength);
                        }
                    }

                    position += nextOffset;
                    downstream.onNext(slicedData);
                } finally {
                    data.close();
                }
            }

            assert position <= end;
            if (position == end) {
                onComplete();
                upstream.cancel();
                cleanup();
            } else {
                demand--;
            }
            return true;
        }

        private void requestOneOrCancel() {
            if (position < end) {
                upstream.request(1);
            } else {
                // Fully received the desired data already.
                upstream.cancel();
            }
        }

        private HttpData retainedSlice(HttpData data, int offset, int length) {
            final ByteBuf byteBuf = data.byteBuf(offset, length, ByteBufAccessMode.RETAINED_DUPLICATE);
            return HttpData.wrap(byteBuf);
        }

        @Override
        public void onError(Throwable t) {
            requireNonNull(t, "t");
            if (completed) {
                return;
            }
            completed = true;
            cleanup();
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            if (buffer != null) {
                if (!completed) {
                    // A fixed stream can send `onComplete()` right after `onNext()` so `abort()` called amid
                    // `onNext()` can be ignored by the upstream.
                    final Throwable abortedCause = DefaultByteStreamMessage.this.abortedCause;
                    if (abortedCause != null) {
                        completed = true;
                        cleanup();
                        downstream.onError(abortedCause);
                        return;
                    }
                }

                // Signal downstream after the buffer is delivered.
                completing = true;
                return;
            }
            if (completed) {
                return;
            }
            completed = true;
            downstream.onComplete();
        }

        @Override
        public void request(long n) {
            if (executor.inEventLoop()) {
                request0(n);
            } else {
                executor.execute(() -> request0(n));
            }
        }

        private void request0(long n) {
            if (n <= 0) {
                onError(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                upstream.cancel();
                return;
            }

            if (completed) {
                return;
            }

            if (completing && buffer == null) {
                downstream.onComplete();
                return;
            }

            final long oldDemand = demand;
            demand += n;
            if (oldDemand == 0 && buffer == null) {
                upstream.request(1);
            } else if (buffer != null && !inOnNext) {
                // If inOnNext is true, the new demand will be handled by the while-loop in `onNext()`
                final HttpData buffer = this.buffer;
                this.buffer = null;
                onNext(buffer);
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
            if (completed) {
                return;
            }
            completed = true;
            cleanup();
            if (notifyCancellation) {
                downstream.onError(CancelledSubscriptionException.get());
            }
            upstream.cancel();
        }

        private void cleanup() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            demand = 0;
        }
    }
}
