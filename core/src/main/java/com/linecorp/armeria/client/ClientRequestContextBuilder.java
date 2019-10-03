/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URI;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.AbstractRequestContextBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;

/**
 * Builds a new {@link ClientRequestContext}. Note that it is not usually required to create a new context by
 * yourself, because Armeria will always provide a context object for you. However, it may be useful in some
 * cases such as unit testing.
 */
public final class ClientRequestContextBuilder extends AbstractRequestContextBuilder {

    /**
     * Returns a new {@link ClientRequestContextBuilder} created from the specified {@link HttpRequest}.
     */
    public static ClientRequestContextBuilder of(HttpRequest request) {
        return new ClientRequestContextBuilder(request);
    }

    /**
     * Returns a new {@link ClientRequestContextBuilder} created from the specified {@link RpcRequest} and URI.
     */
    public static ClientRequestContextBuilder of(RpcRequest request, String uri) {
        return of(request, URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link ClientRequestContextBuilder} created from the specified {@link RpcRequest} and
     * {@link URI}.
     */
    public static ClientRequestContextBuilder of(RpcRequest request, URI uri) {
        return new ClientRequestContextBuilder(request, uri);
    }

    @Nullable
    private final String fragment;
    @Nullable
    private Endpoint endpoint;
    private ClientOptions options = ClientOptions.DEFAULT;

    private ClientRequestContextBuilder(HttpRequest request) {
        super(false, request);
        fragment = null;
    }

    private ClientRequestContextBuilder(RpcRequest request, URI uri) {
        super(false, request, uri);
        fragment = uri.getRawFragment();
    }

    @Override
    public ClientRequestContextBuilder method(HttpMethod method) {
        super.method(method);
        return this;
    }

    /**
     * Sets the {@link Endpoint} of the request. If not set, it is auto-generated from the request authority.
     */
    public ClientRequestContextBuilder endpoint(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Sets the {@link ClientOptions} of the client. If not set, {@link ClientOptions#DEFAULT} is used.
     */
    public ClientRequestContextBuilder options(ClientOptions options) {
        this.options = requireNonNull(options, "options");
        return this;
    }

    /**
     * Returns a new {@link ClientRequestContext} created with the properties of this builder.
     */
    public ClientRequestContext build() {
        final Endpoint endpoint;
        if (this.endpoint != null) {
            endpoint = this.endpoint;
        } else {
            endpoint = Endpoint.parse(authority());
        }

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                eventLoop(), meterRegistry(), sessionProtocol(),
                method(), path(), query(), fragment, options, request(), rpcRequest());
        ctx.init(endpoint);

        if (isRequestStartTimeSet()) {
            ctx.logBuilder().startRequest(fakeChannel(), sessionProtocol(), sslSession(),
                                          requestStartTimeNanos(), requestStartTimeMicros());
        } else {
            ctx.logBuilder().startRequest(fakeChannel(), sessionProtocol(), sslSession());
        }

        if (request() != null) {
            ctx.logBuilder().requestHeaders(request().headers());
        }

        if (rpcRequest() != null) {
            ctx.logBuilder().requestContent(rpcRequest(), null);
        }

        return ctx;
    }

    // Methods that were overridden to change the return type.

    @Override
    public ClientRequestContextBuilder meterRegistry(MeterRegistry meterRegistry) {
        return (ClientRequestContextBuilder) super.meterRegistry(meterRegistry);
    }

    @Override
    public ClientRequestContextBuilder eventLoop(EventLoop eventLoop) {
        return (ClientRequestContextBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public ClientRequestContextBuilder alloc(ByteBufAllocator alloc) {
        return (ClientRequestContextBuilder) super.alloc(alloc);
    }

    @Override
    public ClientRequestContextBuilder sessionProtocol(SessionProtocol sessionProtocol) {
        return (ClientRequestContextBuilder) super.sessionProtocol(sessionProtocol);
    }

    @Override
    public ClientRequestContextBuilder remoteAddress(InetSocketAddress remoteAddress) {
        return (ClientRequestContextBuilder) super.remoteAddress(remoteAddress);
    }

    @Override
    public ClientRequestContextBuilder localAddress(InetSocketAddress localAddress) {
        return (ClientRequestContextBuilder) super.localAddress(localAddress);
    }

    @Override
    public ClientRequestContextBuilder sslSession(SSLSession sslSession) {
        return (ClientRequestContextBuilder) super.sslSession(sslSession);
    }

    @Override
    public ClientRequestContextBuilder requestStartTime(long requestStartTimeNanos,
                                                        long requestStartTimeMicros) {
        return (ClientRequestContextBuilder) super.requestStartTime(requestStartTimeNanos,
                                                                    requestStartTimeMicros);
    }
}
