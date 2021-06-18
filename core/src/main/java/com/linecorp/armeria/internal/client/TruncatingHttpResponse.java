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

package com.linecorp.armeria.internal.client;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A variant of {@link HttpResponse} that limits the maximum length of the content.
 */
public final class TruncatingHttpResponse extends FilteredHttpResponse {

    private final int maxContentLength;

    private int contentLength;
    private boolean overflow;
    @Nullable
    private Subscriber<? super HttpObject> subscriber;

    public TruncatingHttpResponse(HttpResponse delegate, int maxContentLength) {
        super(delegate);
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected void beforeSubscribe(Subscriber<? super HttpObject> subscriber, Subscription subscription) {
        this.subscriber = subscriber;
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (!overflow && obj instanceof HttpData) {
            final int dataLength = ((HttpData) obj).length();
            contentLength += dataLength;
            if (contentLength > maxContentLength) {
                overflow = true;
                assert subscriber != null;
                subscriber.onComplete();
            }
        }
        return obj;
    }
}
