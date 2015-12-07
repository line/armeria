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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.function.Function;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;

/**
 * A {@link ServiceCodec} that decorates another {@link ServiceCodec}. Do not use this class unless you want
 * to define a new dedicated {@link ServiceCodec} type by extending this class; prefer:
 * <ul>
 *   <li>{@link Service#decorate(Function)}</li>
 *   <li>{@link Service#decorateCodec(Function)}</li>
 *   <li>{@link Service#newDecorator(Function, Function)}</li>
 * </ul>
 */
public abstract class DecoratingServiceCodec implements ServiceCodec {

    private final ServiceCodec delegate;

    /**
     * Creates a new instance that decorates the specified {@link ServiceCodec}.
     */
    protected DecoratingServiceCodec(ServiceCodec delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link ServiceCodec} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends ServiceCodec> T delegate() {
        return (T) delegate;
    }

    @Override
    public void codecAdded(ServiceConfig cfg) throws Exception {
        ServiceCallbackInvoker.invokeCodecAdded(cfg, delegate());
    }

    @Override
    public DecodeResult decodeRequest(ServiceConfig cfg, Channel ch, SessionProtocol sessionProtocol,
                                      String hostname, String path, String mappedPath, ByteBuf in,
                                      Object originalRequest, Promise<Object> promise) throws Exception {
        return delegate().decodeRequest(cfg, ch, sessionProtocol, hostname,
                                        path, mappedPath, in, originalRequest, promise);
    }

    @Override
    public boolean failureResponseFailsSession(ServiceInvocationContext ctx) {
        return delegate().failureResponseFailsSession(ctx);
    }

    @Override
    public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
        return delegate().encodeResponse(ctx, response);
    }

    @Override
    public ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception {
        return delegate().encodeFailureResponse(ctx, cause);
    }

    @Override
    public final <T extends ServiceCodec> Optional<T> as(Class<T> codecType) {
        final Optional<T> result = ServiceCodec.super.as(codecType);
        return result.isPresent() ? result : delegate().as(codecType);
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate() + ')';
    }
}
