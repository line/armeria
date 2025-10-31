/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.internal;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpPreClient;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

import io.netty.util.AttributeKey;

public final class DelegatingHttpClient implements HttpClient, HttpPreClient {

    private static final DelegatingHttpClient INSTANCE = new DelegatingHttpClient();

    private static final AttributeKey<Client<HttpRequest, HttpResponse>> CLIENT_DELEGATE_KEY =
            AttributeKey.valueOf(DelegatingHttpClient.class, "DELEGATE_KEY");
    private static final AttributeKey<PreClient<HttpRequest, HttpResponse>> PRECLIENT_DELEGATE_KEY =
            AttributeKey.valueOf(DelegatingHttpClient.class, "DELEGATE_KEY");

    public static DelegatingHttpClient of() {
        return INSTANCE;
    }

    public static void setDelegate(ClientRequestContext ctx, HttpClient delegate) {
        ctx.setAttr(CLIENT_DELEGATE_KEY, delegate);
    }

    public static void setDelegate(PreClientRequestContext ctx, PreClient<HttpRequest, HttpResponse> delegate) {
        ctx.setAttr(PRECLIENT_DELEGATE_KEY, delegate);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Client<HttpRequest, HttpResponse> delegate = ctx.attr(CLIENT_DELEGATE_KEY);
        if (delegate == null) {
            throw missingDelegateException();
        }
        return delegate.execute(ctx, req);
    }

    @Override
    public HttpResponse execute(PreClientRequestContext ctx, HttpRequest req) throws Exception {
        final PreClient<HttpRequest, HttpResponse> delegate = ctx.attr(PRECLIENT_DELEGATE_KEY);
        if (delegate == null) {
            throw missingDelegateException();
        }
        return delegate.execute(ctx, req);
    }

    static UnprocessedRequestException missingDelegateException() {
        return UnprocessedRequestException.of(new IllegalArgumentException(
                "The delegate is not set for the ctx. If a new ctx has been used, " +
                "please make sure to use ctx.newDerivedContext()."));
    }

    private DelegatingHttpClient() {
    }
}
