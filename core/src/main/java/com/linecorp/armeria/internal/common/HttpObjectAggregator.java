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

package com.linecorp.armeria.internal.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public abstract class HttpObjectAggregator<T extends AggregatedHttpObject> implements Subscriber<HttpObject> {

    private final CompletableFuture<T> future;
    private final List<HttpData> contentList = new ArrayList<>();
    // ByteBufAllocator is only provided when pooled objects are requested.
    @Nullable
    private final ByteBufAllocator alloc;
    private int contentLength;
    @Nullable
    private Subscription subscription;

    protected HttpObjectAggregator(CompletableFuture<T> future,
                                   @Nullable ByteBufAllocator alloc) {
        this.future = future;
        this.alloc = alloc;
    }

    @Override
    public final void onSubscribe(Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public final void onError(Throwable t) {
        fail(t);
    }

    @Override
    public final void onComplete() {
        if (future.isDone()) {
            return;
        }

        final HttpData content;
        if (contentLength == 0) {
            content = HttpData.empty();
        } else {
            if (alloc != null) {
                final ByteBuf merged = alloc.buffer(contentLength);
                for (int i = 0; i < contentList.size(); i++) {
                    try (HttpData data = contentList.set(i, null)) {
                        final ByteBuf buf = data.byteBuf();
                        merged.writeBytes(buf, buf.readerIndex(), data.length());
                    }
                }
                content = HttpData.wrap(merged);
            } else {
                final byte[] merged = new byte[contentLength];
                for (int i = 0, offset = 0; i < contentList.size(); i++) {
                    final HttpData data = contentList.set(i, null);
                    final int dataLength = data.length();
                    System.arraycopy(data.array(), 0, merged, offset, dataLength);
                    offset += dataLength;
                }
                content = HttpData.wrap(merged);
            }
            contentList.clear();
        }

        try {
            future.complete(onSuccess(content));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
    }

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
                data.close();
            }
        }
    }

    private void fail(Throwable cause) {
        contentList.forEach(HttpData::close);
        contentList.clear();
        onFailure();
        future.completeExceptionally(cause);
    }

    protected abstract T onSuccess(HttpData content);

    protected abstract void onFailure();
}
