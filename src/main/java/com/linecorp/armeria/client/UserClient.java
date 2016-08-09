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

package com.linecorp.armeria.client;

import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContext.PushHandle;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.EventLoop;

public abstract class UserClient<T, I extends Request, O extends Response> implements ClientOptionDerivable<T> {

    private final Client<I, O> delegate;
    private final Supplier<EventLoop> eventLoopSupplier;
    private final SessionProtocol sessionProtocol;
    private final ClientOptions options;
    private final Endpoint endpoint;

    protected UserClient(Client<I, O> delegate, Supplier<EventLoop> eventLoopSupplier,
                         SessionProtocol sessionProtocol, ClientOptions options, Endpoint endpoint) {

        this.delegate = delegate;
        this.eventLoopSupplier = eventLoopSupplier;
        this.sessionProtocol = sessionProtocol;
        this.options = options;
        this.endpoint = endpoint;
    }

    @SuppressWarnings("unchecked")
    protected final <U extends Client<I, O>> U delegate() {
        return (U) delegate;
    }

    protected final EventLoop eventLoop() {
        return eventLoopSupplier.get();
    }

    protected final SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    protected final ClientOptions options() {
        return options;
    }

    protected final Endpoint endpoint() {
        return endpoint;
    }

    protected final O execute(
            String method, String path, I req, Function<Throwable, O> fallback) {
        return execute(eventLoop(), method, path, req, fallback);
    }

    protected final O execute(
            EventLoop eventLoop, String method, String path, I req, Function<Throwable, O> fallback) {

        final ClientRequestContext ctx = new DefaultClientRequestContext(
                eventLoop, sessionProtocol, endpoint, method, path, options, req);
        try (PushHandle ignored = RequestContext.push(ctx)) {
            return delegate().execute(ctx, req);
        } catch (Throwable cause) {
            ctx.responseLogBuilder().end(cause);
            return fallback.apply(cause);
        }
    }

    @Override
    public final T withOptions(ClientOptionValue<?>... additionalOptions) {
        final ClientOptions options = ClientOptions.of(options(), additionalOptions);
        return newInstance(delegate(), eventLoopSupplier, sessionProtocol(), options, endpoint());
    }

    @Override
    public final T withOptions(Iterable<ClientOptionValue<?>> additionalOptions) {
        final ClientOptions options = ClientOptions.of(options(), additionalOptions);
        return newInstance(delegate(), eventLoopSupplier, sessionProtocol(), options, endpoint());
    }

    protected abstract T newInstance(Client<I, O> delegate, Supplier<EventLoop> eventLoopSupplier,
                                     SessionProtocol sessionProtocol, ClientOptions options, Endpoint endpoint);
}
