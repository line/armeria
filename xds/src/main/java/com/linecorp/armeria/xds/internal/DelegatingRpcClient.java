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

import static com.linecorp.armeria.xds.internal.DelegatingHttpClient.missingDelegateException;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcPreClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

import io.netty.util.AttributeKey;

public final class DelegatingRpcClient implements RpcClient, RpcPreClient {

    private static final DelegatingRpcClient INSTANCE = new DelegatingRpcClient();

    private static final AttributeKey<Client<RpcRequest, RpcResponse>> CLIENT_DELEGATE_KEY =
            AttributeKey.valueOf(DelegatingRpcClient.class, "DELEGATE_KEY");

    private static final AttributeKey<PreClient<RpcRequest, RpcResponse>> PRECLIENT_DELEGATE_KEY =
            AttributeKey.valueOf(DelegatingRpcClient.class, "DELEGATE_KEY");

    public static DelegatingRpcClient of() {
        return INSTANCE;
    }

    public static void setDelegate(ClientRequestContext ctx, RpcClient delegate) {
        ctx.setAttr(CLIENT_DELEGATE_KEY, delegate);
    }

    public static void setDelegate(PreClientRequestContext ctx, PreClient<RpcRequest, RpcResponse> delegate) {
        ctx.setAttr(PRECLIENT_DELEGATE_KEY, delegate);
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final Client<RpcRequest, RpcResponse> delegate = ctx.attr(CLIENT_DELEGATE_KEY);
        if (delegate == null) {
            throw missingDelegateException();
        }
        return delegate.execute(ctx, req);
    }

    @Override
    public RpcResponse execute(PreClientRequestContext ctx, RpcRequest req) throws Exception {
        final PreClient<RpcRequest, RpcResponse> delegate = ctx.attr(PRECLIENT_DELEGATE_KEY);
        if (delegate == null) {
            throw missingDelegateException();
        }
        return delegate.execute(ctx, req);
    }

    private DelegatingRpcClient() {
    }
}
