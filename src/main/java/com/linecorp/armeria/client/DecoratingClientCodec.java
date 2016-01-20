/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;

/**
 * A {@link ClientCodec} that decorates another {@link ClientCodec}.
 *
 * @see ClientOption#DECORATOR
 */
public abstract class DecoratingClientCodec implements ClientCodec {

    private final ClientCodec delegate;

    /**
     * Creates a new instance that decorates the specified {@link ClientCodec}.
     */
    protected DecoratingClientCodec(ClientCodec delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link ClientCodec} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends ClientCodec> T delegate() {
        return (T) delegate;
    }

    @Override
    public <T> void prepareRequest(Method method, Object[] args,
                                   Promise<T> resultPromise) {
        delegate().prepareRequest(method, args, resultPromise);
    }

    @Override
    public EncodeResult encodeRequest(Channel channel, SessionProtocol sessionProtocol, Method method,
                                      Object[] args) {
        return delegate().encodeRequest(channel, sessionProtocol, method, args);
    }

    @Override
    public <T> T decodeResponse(ServiceInvocationContext ctx,
                                ByteBuf content, Object originalResponse) throws Exception {
        return delegate().decodeResponse(ctx, content, originalResponse);
    }

    @Override
    public boolean isAsyncClient() {
        return delegate().isAsyncClient();
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate() + ')';
    }
}
