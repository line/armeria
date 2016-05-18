/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

abstract class HttpMessageAggregator implements Subscriber<HttpObject> {

    private final CompletableFuture<AggregatedHttpMessage> future;
    protected final List<HttpData> contentList = new ArrayList<>();
    protected int contentLength;
    private Subscription subscription;

    protected HttpMessageAggregator(CompletableFuture<AggregatedHttpMessage> future) {
        this.future = future;
    }

    protected final CompletableFuture<AggregatedHttpMessage> future() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    protected final void add(HttpData data) {
        final int dataLength = data.length();
        if (dataLength > 0) {
            if (contentLength > Integer.MAX_VALUE - dataLength) {
                clear();
                subscription.cancel();
                throw new IllegalStateException("content length greater than Integer.MAX_VALUE");
            }

            contentList.add(data);
            contentLength += dataLength;
        }
    }

    protected final void clear() {
        doClear();
        contentList.clear();
    }

    protected void doClear() {}

    protected final HttpData finish() {
        final HttpData content;
        if (contentLength == 0) {
            content = HttpData.EMPTY_DATA;
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
        return content;
    }
}
