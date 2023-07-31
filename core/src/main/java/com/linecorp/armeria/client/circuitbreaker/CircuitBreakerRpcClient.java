/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;

/**
 * An {@link RpcClient} decorator that handles failures of RPC remote invocation based on
 * circuit breaker pattern.
 */
public final class CircuitBreakerRpcClient extends AbstractCircuitBreakerClient<RpcRequest, RpcResponse>
        implements RpcClient {

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param circuitBreaker The {@link CircuitBreaker} instance to be used
     */
    public static Function<? super RpcClient, CircuitBreakerRpcClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        requireNonNull(ruleWithContent, "ruleWithContent");
        return newDecorator(CircuitBreakerClientHandler.of(circuitBreaker), ruleWithContent);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerMapping} and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super RpcClient, CircuitBreakerRpcClient>
    newDecorator(CircuitBreakerMapping mapping, CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        requireNonNull(mapping, "mapping");
        requireNonNull(ruleWithContent, "ruleWithContent");
        return newDecorator(CircuitBreakerClientHandler.of(mapping), ruleWithContent);
    }

    /**
     * Creates a new decorator with the specified {@link CircuitBreakerClientHandler} and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    @UnstableApi
    public static Function<? super RpcClient, CircuitBreakerRpcClient>
    newDecorator(CircuitBreakerClientHandler handler,
                 CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        requireNonNull(handler, "handler");
        requireNonNull(ruleWithContent, "ruleWithContent");
        return delegate -> new CircuitBreakerRpcClient(delegate, handler, ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per RPC method name with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory A function that takes an RPC method name and creates a new {@link CircuitBreaker}.
     */
    public static Function<? super RpcClient, CircuitBreakerRpcClient>
    newPerMethodDecorator(Function<String, ? extends CircuitBreaker> factory,
                          CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        return newDecorator(CircuitBreakerMapping.perMethod(factory), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host name and creates a new {@link CircuitBreaker}
     */
    public static Function<? super RpcClient, CircuitBreakerRpcClient>
    newPerHostDecorator(Function<String, ? extends CircuitBreaker> factory,
                        CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        return newDecorator(CircuitBreakerMapping.perHost(factory), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and RPC method name with
     * the specified {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param factory a function that takes a host+method and creates a new {@link CircuitBreaker}
     *
     * @deprecated Use {@link #newDecorator(CircuitBreakerMapping, CircuitBreakerRuleWithContent)} with
     *             {@link CircuitBreakerMapping#perHostAndMethod(BiFunction)}.
     */
    @Deprecated
    public static Function<? super RpcClient, CircuitBreakerRpcClient>
    newPerHostAndMethodDecorator(BiFunction<String, String, ? extends CircuitBreaker> factory,
                                 CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        return newDecorator(CircuitBreakerMapping.perHostAndMethod(factory), ruleWithContent);
    }

    /**
     * Returns a new {@link CircuitBreakerRpcClientBuilder} with
     * the specified {@link CircuitBreakerRuleWithContent}.
     */
    public static CircuitBreakerRpcClientBuilder builder(
            CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        return new CircuitBreakerRpcClientBuilder(ruleWithContent);
    }

    /**
     * Creates a new instance that decorates the specified {@link RpcClient}.
     */
    CircuitBreakerRpcClient(RpcClient delegate, CircuitBreakerClientHandler handler,
                            CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent) {
        this(delegate, handler, requireNonNull(ruleWithContent, "ruleWithContent"), null);
    }

    /**
     * Creates a new instance that decorates the specified {@link RpcClient}.
     */
    CircuitBreakerRpcClient(
            RpcClient delegate, CircuitBreakerClientHandler handler,
            CircuitBreakerRuleWithContent<RpcResponse> ruleWithContent,
            @Nullable BiFunction<? super ClientRequestContext, ? super RpcRequest, ? extends RpcResponse>
                    fallback) {
        super(delegate, handler, requireNonNull(ruleWithContent, "ruleWithContent"), fallback);
    }

    @Override
    protected RpcResponse doExecute(ClientRequestContext ctx, RpcRequest req, CircuitBreakerCallback callback)
            throws Exception {
        final RpcResponse response;
        try {
            response = unwrap().execute(ctx, req);
        } catch (Throwable cause) {
            reportSuccessOrFailure(
                    callback, ruleWithContent().shouldReportAsSuccess(ctx, null, cause), ctx, cause);
            throw cause;
        }

        response.handle((unused1, cause) -> {
            reportSuccessOrFailure(
                    callback, ruleWithContent().shouldReportAsSuccess(ctx, response, cause), ctx, cause);
            return null;
        });
        return response;
    }
}
