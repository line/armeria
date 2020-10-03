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

import java.net.SocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.AbstractRequestContextBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.TimeoutScheduler;
import com.linecorp.armeria.internal.common.TimeoutScheduler.TimeoutTask;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Builds a new {@link ClientRequestContext}. Note that it is not usually required to create a new context by
 * yourself, because Armeria will always provide a context object for you. However, it may be useful in some
 * cases such as unit testing.
 */
public final class ClientRequestContextBuilder extends AbstractRequestContextBuilder {

    private static final TimeoutTask noopTimeoutTask = new TimeoutTask() {
        @Override
        public boolean canSchedule() {
            return true;
        }

        @Override
        public void run() { /* no-op */ }
    };

    /**
     * A timeout scheduler that has been timed-out.
     */
    private static final TimeoutScheduler noopResponseCancellationScheduler = new TimeoutScheduler(0);

    static {
        noopResponseCancellationScheduler.init(ImmediateEventExecutor.INSTANCE, noopTimeoutTask, 0);
        noopResponseCancellationScheduler.finishNow();
    }

    @Nullable
    private final String fragment;
    @Nullable
    private Endpoint endpoint;
    private ClientOptions options = ClientOptions.of();
    @Nullable
    private ClientConnectionTimings connectionTimings;

    ClientRequestContextBuilder(HttpRequest request) {
        super(false, request);
        fragment = null;
    }

    ClientRequestContextBuilder(RpcRequest request, URI uri) {
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
     * Sets the {@link ClientOptions} of the client. If not set, {@link ClientOptions#of()} is used.
     */
    public ClientRequestContextBuilder options(ClientOptions options) {
        this.options = requireNonNull(options, "options");
        return this;
    }

    /**
     * Sets the {@link ClientConnectionTimings} of the request.
     */
    public ClientRequestContextBuilder connectionTimings(ClientConnectionTimings connectionTimings) {
        this.connectionTimings = requireNonNull(connectionTimings, "connectionTimings");
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

        final TimeoutScheduler responseCancellationScheduler;
        if (timedOut()) {
            responseCancellationScheduler = noopResponseCancellationScheduler;
        } else {
            responseCancellationScheduler = new TimeoutScheduler(0);
            final CountDownLatch latch = new CountDownLatch(1);
            eventLoop().execute(() -> {
                responseCancellationScheduler.init(eventLoop(), noopTimeoutTask, 0);
                latch.countDown();
            });

            try {
                latch.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                eventLoop(), meterRegistry(), sessionProtocol(),
                id(), method(), path(), query(), fragment, options, request(), rpcRequest(),
                responseCancellationScheduler,
                isRequestStartTimeSet() ? requestStartTimeNanos() : System.nanoTime(),
                isRequestStartTimeSet() ? requestStartTimeMicros() : SystemInfo.currentTimeMicros());

        ctx.init(endpoint);
        ctx.logBuilder().session(fakeChannel(), sessionProtocol(), sslSession(), connectionTimings);

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
    public ClientRequestContextBuilder id(RequestId id) {
        return (ClientRequestContextBuilder) super.id(id);
    }

    @Override
    public ClientRequestContextBuilder remoteAddress(SocketAddress remoteAddress) {
        return (ClientRequestContextBuilder) super.remoteAddress(remoteAddress);
    }

    @Override
    public ClientRequestContextBuilder localAddress(SocketAddress localAddress) {
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

    @Override
    public ClientRequestContextBuilder timedOut(boolean timedOut) {
        return (ClientRequestContextBuilder) super.timedOut(timedOut);
    }
}
