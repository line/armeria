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

import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.SystemInfo;

import io.micrometer.core.instrument.MeterRegistry;

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

    private static final Logger logger = LoggerFactory.getLogger(UserClient.class);
    private static boolean warnedNullRequestId;

    private final ClientBuilderParams params;
    private final MeterRegistry meterRegistry;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;

    /**
     * Creates a new instance.
     *
     * @param params the parameters used for constructing the client
     * @param delegate the {@link Client} that will process {@link Request}s
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param futureConverter the {@link Function} that converts a {@link CompletableFuture} of response
     *                        into a response, e.g. {@link HttpResponse#from(CompletionStage)}
     *                        and {@link RpcResponse#from(CompletionStage)}
     * @param errorResponseFactory the {@link BiFunction} that returns a new response failed with
     *                             the given exception
     */
    protected UserClient(ClientBuilderParams params, Client<I, O> delegate, MeterRegistry meterRegistry,
                         Function<CompletableFuture<O>, O> futureConverter,
                         BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        super(delegate);
        this.params = params;
        this.meterRegistry = meterRegistry;
        this.futureConverter = futureConverter;
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public final Scheme scheme() {
        return params.scheme();
    }

    @Override
    public final EndpointGroup endpointGroup() {
        return params.endpointGroup();
    }

    @Override
    public final String absolutePathRef() {
        return params.absolutePathRef();
    }

    @Override
    public final URI uri() {
        return params.uri();
    }

    @Override
    public final Class<?> clientType() {
        return params.clientType();
    }

    @Override
    public final ClientOptions options() {
        return params.options();
    }

    /**
     * Executes the specified {@link Request} via the delegate.
     *
     * @param protocol the {@link SessionProtocol} to use
     * @param method the method of the {@link Request}
     * @param path the path part of the {@link Request} URI
     * @param query the query part of the {@link Request} URI
     * @param fragment the fragment part of the {@link Request} URI
     * @param req the {@link Request}
     */
    protected final O execute(SessionProtocol protocol, HttpMethod method, String path,
                              @Nullable String query, @Nullable String fragment, I req) {
        return execute(protocol, endpointGroup(), method, path, query, fragment, req, RequestOptions.of());
    }

    /**
     * Executes the specified {@link Request} via the delegate.
     *
     * @param protocol the {@link SessionProtocol} to use
     * @param endpointGroup the {@link EndpointGroup} of the {@link Request}
     * @param method the method of the {@link Request}
     * @param path the path part of the {@link Request} URI
     * @param query the query part of the {@link Request} URI
     * @param fragment the fragment part of the {@link Request} URI
     * @param req the {@link Request}
     */
    protected final O execute(SessionProtocol protocol, EndpointGroup endpointGroup, HttpMethod method,
                              String path, @Nullable String query, @Nullable String fragment, I req) {
        return execute(protocol, endpointGroup, method, path, query, fragment, req, RequestOptions.of());
    }

    /**
     * Executes the specified {@link Request} via the delegate.
     *
     * @param protocol the {@link SessionProtocol} to use
     * @param endpointGroup the {@link EndpointGroup} of the {@link Request}
     * @param method the method of the {@link Request}
     * @param path the path part of the {@link Request} URI
     * @param query the query part of the {@link Request} URI
     * @param fragment the fragment part of the {@link Request} URI
     * @param req the {@link Request}
     * @param requestOptions the {@link RequestOptions} of the {@link Request}
     */
    protected final O execute(SessionProtocol protocol, EndpointGroup endpointGroup, HttpMethod method,
                              String path, @Nullable String query, @Nullable String fragment, I req,
                              RequestOptions requestOptions) {

        final HttpRequest httpReq;
        final RpcRequest rpcReq;
        final RequestId id = nextRequestId();

        if (req instanceof HttpRequest) {
            httpReq = (HttpRequest) req;
            rpcReq = null;
        } else {
            httpReq = null;
            rpcReq = (RpcRequest) req;
        }

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                meterRegistry, protocol, id, method, path, query, fragment, options(), httpReq, rpcReq,
                requestOptions, System.nanoTime(), SystemInfo.currentTimeMicros());

        return initContextAndExecuteWithFallback(unwrap(), ctx, endpointGroup,
                                                 futureConverter, errorResponseFactory);
    }

    private RequestId nextRequestId() {
        final RequestId id = options().requestIdGenerator().get();
        if (id == null) {
            if (!warnedNullRequestId) {
                warnedNullRequestId = true;
                logger.warn("requestIdGenerator.get() returned null; using RequestId.random()");
            }
            return RequestId.random();
        } else {
            return id;
        }
    }
}
