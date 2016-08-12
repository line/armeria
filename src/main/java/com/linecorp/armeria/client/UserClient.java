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

/**
 * A base class for implementing a user's entry point for sending a {@link Request}.
 *
 * <p>It provides the utility methods for easily forwarding a {@link Request} from a user to a {@link Client},
 * as well as the default implementation of {@link ClientOptionDerivable}.
 *
 * <p>Note that this class is not a subtype of {@link Client}, although its name may mislead.
 *
 * @param <T> the self type
 * @param <I> the request type
 * @param <O> the response type
 */
public abstract class UserClient<T, I extends Request, O extends Response> implements ClientOptionDerivable<T> {

    private final Client<I, O> delegate;
    private final Supplier<EventLoop> eventLoopSupplier;
    private final SessionProtocol sessionProtocol;
    private final ClientOptions options;
    private final Endpoint endpoint;

    /**
     * Creates a new instance.
     *
     * @param delegate the {@link Client} that will process {@link Request}s
     * @param eventLoopSupplier the {@link Supplier} that yields an {@link EventLoop} for each {@link Request}
     * @param sessionProtocol the {@link SessionProtocol} of the {@link Client}
     * @param options the {@link ClientOptions} of the {@link Client}
     * @param endpoint the {@link Endpoint} of the {@link Client}
     */
    protected UserClient(Client<I, O> delegate, Supplier<EventLoop> eventLoopSupplier,
                         SessionProtocol sessionProtocol, ClientOptions options, Endpoint endpoint) {

        this.delegate = delegate;
        this.eventLoopSupplier = eventLoopSupplier;
        this.sessionProtocol = sessionProtocol;
        this.options = options;
        this.endpoint = endpoint;
    }

    /**
     * Returns the {@link Client} that will process {@link Request}s.
     */
    @SuppressWarnings("unchecked")
    protected final <U extends Client<I, O>> U delegate() {
        return (U) delegate;
    }

    /**
     * Retrieves an {@link EventLoop} from the {@link Supplier} specified in
     * {@link #UserClient(Client, Supplier, SessionProtocol, ClientOptions, Endpoint)}.
     */
    protected final EventLoop eventLoop() {
        return eventLoopSupplier.get();
    }

    /**
     * Returns the {@link SessionProtocol} of the {@link #delegate()}.
     */
    protected final SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the {@link ClientOptions} of the {@link #delegate()}.
     */
    protected final ClientOptions options() {
        return options;
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
     * @param path the path of the {@link Request}
     * @param req the {@link Request}
     * @param fallback the fallback response {@link Function} to use when
     *                 {@link Client#execute(ClientRequestContext, Request)} of {@link #delegate()} throws
     *                 an exception instead of returning an error response
     */
    protected final O execute(
            String method, String path, I req, Function<Throwable, O> fallback) {
        return execute(eventLoop(), method, path, req, fallback);
    }

    /**
     * Executes the specified {@link Request} via {@link #delegate()}.
     *
     * @param eventLoop the {@link EventLoop} to execute the {@link Request}
     * @param method the method of the {@link Request}
     * @param path the path of the {@link Request}
     * @param req the {@link Request}
     * @param fallback the fallback response {@link Function} to use when
     *                 {@link Client#execute(ClientRequestContext, Request)} of {@link #delegate()} throws
     *                 an exception instead of returning an error response
     */
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

    /**
     * Creates a new instance of the same type with this client.
     *
     * @param delegate the {@link Client} that will process {@link Request}s
     * @param eventLoopSupplier the {@link Supplier} that yields an {@link EventLoop} for each {@link Request}
     * @param sessionProtocol the {@link SessionProtocol} of the {@link Client}
     * @param options the {@link ClientOptions} of the {@link Client}
     * @param endpoint the {@link Endpoint} of the {@link Client}
     */
    protected abstract T newInstance(Client<I, O> delegate, Supplier<EventLoop> eventLoopSupplier,
                                     SessionProtocol sessionProtocol, ClientOptions options, Endpoint endpoint);
}
