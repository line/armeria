/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCountUtil;

abstract class HttpMessageAggregator implements Subscriber<HttpObject>, BiFunction<Void, Throwable, Void> {

    private final CompletableFuture<AggregatedHttpMessage> future;
    private final List<HttpData> contentList = new ArrayList<>();
    // ByteBufAllocator is only provided when pooled objects are requested.
    @Nullable
    private final ByteBufAllocator alloc;
    private int contentLength;
    @Nullable
    private Subscription subscription;

    HttpMessageAggregator(CompletableFuture<AggregatedHttpMessage> future,
                          @Nullable ByteBufAllocator alloc) {
        this.future = future;
        this.alloc = alloc;
    }

    @Override
    public final void onSubscribe(Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    /**
     * Handled by {@link #apply(Void, Throwable)} instead,
     * because this method is not invoked on cancellation and timeout.
     */
    @Override
    public final void onError(Throwable throwable) {}

    /**
     * Handled by {@link #apply(Void, Throwable)} instead,
     * because this method is not invoked on cancellation and timeout.
     */
    @Override
    public final void onComplete() {}

    @Override
    public final void onNext(HttpObject o) {
        if (o instanceof HttpHeaders) {
            onHeaders((HttpHeaders) o);
        } else {
            onData((HttpData) o);
        }
    }

    protected abstract void onHeaders(HttpHeaders headers);

    protected void onData(HttpData data) {
        boolean added = false;
        try {
            if (future.isDone()) {
                return;
            }

            final int dataLength = data.length();
            if (dataLength > 0) {
                final int allowedMaxDataLength = Integer.MAX_VALUE - contentLength;
                if (dataLength > allowedMaxDataLength) {
                    subscription.cancel();
                    fail(new IllegalStateException("content length greater than Integer.MAX_VALUE"));
                    return;
                }

                contentList.add(data);
                contentLength += dataLength;
                added = true;
            }
        } finally {
            if (!added) {
                ReferenceCountUtil.safeRelease(data);
            }
        }
    }

    @Override
    public Void apply(Void unused, Throwable cause) {
        if (cause != null) {
            fail(cause);
            return null;
        }

        final HttpData content;
        if (contentLength == 0) {
            content = HttpData.EMPTY_DATA;
        } else {
            if (alloc != null) {
                final ByteBuf merged = alloc.buffer(contentLength);
                for (int i = 0; i < contentList.size(); i++) {
                    final HttpData data = contentList.set(i, null);
                    if (data instanceof ByteBufHolder) {
                        final ByteBufHolder byteBufData = (ByteBufHolder) data;
                        try {
                            merged.writeBytes(byteBufData.content());
                        } finally {
                            byteBufData.release();
                        }
                    } else {
                        merged.writeBytes(data.array(), data.offset(), data.length());
                    }
                }
                content = new ByteBufHttpData(merged, true);
            } else {
                final byte[] merged = new byte[contentLength];
                for (int i = 0, offset = 0; i < contentList.size(); i++) {
                    final HttpData data = contentList.set(i, null);
                    final int dataLength = data.length();
                    System.arraycopy(data.array(), data.offset(), merged, offset, dataLength);
                    offset += dataLength;
                }
                content = HttpData.of(merged);
            }
        }

        try {
            future.complete(onSuccess(content));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }

        return null;
    }

    private void fail(Throwable cause) {
        contentList.forEach(ReferenceCountUtil::safeRelease);
        contentList.clear();
        onFailure();
        future.completeExceptionally(cause);
    }

    protected abstract AggregatedHttpMessage onSuccess(HttpData content);

    protected abstract void onFailure();
}
