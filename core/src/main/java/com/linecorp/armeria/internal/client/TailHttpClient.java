/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

public final class TailHttpClient implements HttpClient {

    private static final AttributeKey<HttpClient> DELEGATE_KEY =
            AttributeKey.valueOf(TailHttpClient.class, "DELEGATE");

    private final HttpClient defaultDelegate;

    public TailHttpClient(HttpClient defaultDelegate) {
        this.defaultDelegate = defaultDelegate;
    }

    HttpClient defaultDelegate() {
        return defaultDelegate;
    }

    static void setDelegate(ClientRequestContext ctx, HttpClient delegate) {
        ctx.setAttr(DELEGATE_KEY, delegate);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final HttpClient delegate = delegateOrDefault(ctx);
        return delegate.execute(ctx, req);
    }

    private HttpClient delegateOrDefault(ClientRequestContext ctx) {
        final HttpClient override = ctx.attr(DELEGATE_KEY);
        return override != null ? override : defaultDelegate;
    }

    @Nullable
    @Override
    public <T> T as(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        return defaultDelegate.as(type);
    }
}
