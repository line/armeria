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
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;

final class DefaultByteStreamMessage implements ByteStreamMessage {

    private final StreamMessage<? extends HttpData> delegate;

    private long offset;
    private long length = -1;
    private long demand;

    @Nullable
    private volatile Throwable abortedCause;
    private volatile boolean subscribed;

    DefaultByteStreamMessage(StreamMessage<? extends HttpData> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ByteStreamMessage range(long offset, long length) {
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
                    new FilteringSubscriber(subscriber, executor, offset, length),
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
        private final long offset;
        private final long end;
        private final EventExecutor executor;

        @Nullable
        private Subscription upstream;
        private long position;
        private boolean completed;

        FilteringSubscriber(Subscriber<? super HttpData> downstream,
                            EventExecutor executor, long offset, long length) {
            this.downstream = downstream;
            this.executor = executor;
            this.offset = offset;
            if (length == -1) {
                end = Long.MAX_VALUE;
            } else {
                end = LongMath.saturatedAdd(offset, length);
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
            if (completed) {
                data.close();
                return;
            }

            if (position >= end) {
                // Drop tail bytes. Fully received the desired data already.
                data.close();
                upstream.cancel();
                return;
            }

            final int dataSize = data.length();
            final long dataEnd = LongMath.saturatedAdd(position, dataSize);
            final long skipBytes = Math.max(0, LongMath.saturatedSubtract(offset, position));
            final long dropBytes = Math.max(0, LongMath.saturatedSubtract(dataEnd, end));

            if (skipBytes >= dataSize) {
                // Skip the entire data and request the next element.
                data.close();
                position += dataSize;
                requestOneOrCancel();
                return;
            }

            if (skipBytes == 0 && dropBytes == 0) {
                // Use the data as is.
                position += dataSize;
                downstream.onNext(data);
            } else {
                try {
                    // skipBytes and dropBytes are less than dataSize(int).
                    assert dropBytes < dataSize;
                    final int intSkipBytes = (int) skipBytes;
                    final int intDropBytes = (int) dropBytes;
                    final int slicedDataSize = dataSize - intSkipBytes - intDropBytes;
                    final HttpData slicedData = retainedSlice(data, intSkipBytes, slicedDataSize);
                    position += intSkipBytes + slicedDataSize;
                    downstream.onNext(slicedData);
                } finally {
                    data.close();
                }
            }

            assert position <= end;
            if (position == end) {
                onComplete();
                upstream.cancel();
            } else {
                if (--demand > 0) {
                    upstream.request(1);
                }
            }
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
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
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

            final long oldDemand = demand;
            demand += n;
            if (oldDemand == 0) {
                upstream.request(1);
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
            upstream.cancel();
        }
    }
}
