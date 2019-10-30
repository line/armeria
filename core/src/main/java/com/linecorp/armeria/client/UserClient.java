/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.internal.ClientUtil.initContextAndExecuteWithFallback;

import java.net.URI;
import java.util.UUID;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AbstractUnwrappable;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;

/**
 * A base class for implementing a user's entry point for sending a {@link Request}.
 *
 * <p>It provides the utility methods for easily forwarding a {@link Request} from a user to a {@link Client}.
 *
 * <p>Note that this class is not a subtype of {@link Client}, although its name may mislead.
 *
 * @param <I> the request type
 * @param <O> the response type
 */
public abstract class UserClient<I extends Request, O extends Response>
        extends AbstractUnwrappable<Client<I, O>>
        implements ClientBuilderParams {

    private final ClientBuilderParams params;
    private final MeterRegistry meterRegistry;
    private final SessionProtocol sessionProtocol;
    private final Endpoint endpoint;

    /**
     * Creates a new instance.
     *
     * @param params the parameters used for constructing the client
     * @param delegate the {@link Client} that will process {@link Request}s
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param sessionProtocol the {@link SessionProtocol} of the {@link Client}
     * @param endpoint the {@link Endpoint} of the {@link Client}
     */
    protected UserClient(ClientBuilderParams params, Client<I, O> delegate, MeterRegistry meterRegistry,
                         SessionProtocol sessionProtocol, Endpoint endpoint) {
        super(delegate);
        this.params = params;
        this.meterRegistry = meterRegistry;
        this.sessionProtocol = sessionProtocol;
        this.endpoint = endpoint;
    }

    @Override
    public ClientFactory factory() {
        return params.factory();
    }

    @Override
    public URI uri() {
        return params.uri();
    }

    @Override
    public Class<?> clientType() {
        return params.clientType();
    }

    @Override
    public final ClientOptions options() {
        return params.options();
    }

    /**
     * Returns the {@link SessionProtocol} of the {@link #delegate()}.
     */
    protected final SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the {@link Endpoint} of the {@link #delegate()}.
     */
    protected final Endpoint endpoint() {
        return endpoint;
    }

    /**
     * Executes the specified {@link Request} via {@link #delegate()}.
     *
     * @param method the method of the {@link Request}
     * @param path the path part of the {@link Request} URI
     * @param query the query part of the {@link Request} URI
     * @param fragment the fragment part of the {@link Request} URI
     * @param req the {@link Request}
     * @param fallback the fallback response {@link BiFunction} to use when
     *                 {@link Client#execute(ClientRequestContext, Request)} of {@link #delegate()} throws
     *                 an exception instead of returning an error response
     */
    protected final O execute(HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
                              I req, BiFunction<ClientRequestContext, Throwable, O> fallback) {
        return execute(null, endpoint, method, path, query, fragment, req, fallback);
    }

    /**
     * Executes the specified {@link Request} via {@link #delegate()}.
     *
     * @param eventLoop the {@link EventLoop} to execute the {@link Request}
     * @param endpoint the {@link Endpoint} of the {@link Request}
     * @param method the method of the {@link Request}
     * @param path the path part of the {@link Request} URI
     * @param query the query part of the {@link Request} URI
     * @param fragment the fragment part of the {@link Request} URI
     * @param req the {@link Request}
     * @param fallback the fallback response {@link BiFunction} to use when
     *                 {@link Client#execute(ClientRequestContext, Request)} of {@link #delegate()} throws
     */
    protected final O execute(@Nullable EventLoop eventLoop, Endpoint endpoint,
                              HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
                              I req, BiFunction<ClientRequestContext, Throwable, O> fallback) {
        final DefaultClientRequestContext ctx;
        final HttpRequest httpReq;
        final RpcRequest rpcReq;
        final UUID uuid = UUID.randomUUID();
        if (req instanceof HttpRequest) {
            httpReq = (HttpRequest) req;
            rpcReq = null;
        } else {
            httpReq = null;
            rpcReq = (RpcRequest) req;
        }

        if (eventLoop == null) {
            ctx = new DefaultClientRequestContext(factory(), meterRegistry, sessionProtocol,
                                                  uuid, method, path, query, fragment, options(),
                                                  httpReq, rpcReq);
        } else {
            ctx = new DefaultClientRequestContext(eventLoop, meterRegistry, sessionProtocol,
                                                  uuid, method, path, query, fragment, options(),
                                                  httpReq, rpcReq);
        }

        return initContextAndExecuteWithFallback(delegate(), ctx, endpoint, fallback);
    }
}
