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
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

public final class TailRpcClient implements RpcClient {

    private static final AttributeKey<RpcClient> DELEGATE_KEY =
            AttributeKey.valueOf(TailRpcClient.class, "DELEGATE");

    private final RpcClient defaultDelegate;

    public TailRpcClient(RpcClient defaultDelegate) {
        this.defaultDelegate = defaultDelegate;
    }

    RpcClient defaultDelegate() {
        return defaultDelegate;
    }

    static void setDelegate(ClientRequestContext ctx, RpcClient delegate) {
        ctx.setAttr(DELEGATE_KEY, delegate);
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final RpcClient delegate = delegateOrDefault(ctx);
        return delegate.execute(ctx, req);
    }

    private RpcClient delegateOrDefault(ClientRequestContext ctx) {
        final RpcClient override = ctx.attr(DELEGATE_KEY);
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
