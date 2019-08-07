/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Builds a new {@link RetryingRpcClient} or its decorator function.
 */
public class RetryingRpcClientBuilder
        extends RetryingClientBuilder<RetryingRpcClient, RpcRequest, RpcResponse> {

    /**
     * Creates a new builder with the specified {@link RetryStrategyWithContent}.
     */
    public RetryingRpcClientBuilder(RetryStrategyWithContent<RpcResponse> retryStrategyWithContent) {
        super(retryStrategyWithContent);
    }

    /**
     * Returns a newly-created {@link RetryingRpcClient} based on the properties of this builder.
     */
    @Override
    public RetryingRpcClient build(Client<RpcRequest, RpcResponse> delegate) {
        return new RetryingRpcClient(
                delegate, retryStrategyWithContent(), maxTotalAttempts(),
                responseTimeoutMillisForEachAttempt());
    }

    /**
     * Returns a newly-created decorator that decorates a {@link Client} with a new {@link RetryingRpcClient}
     * based on the properties of this builder.
     */
    @Override
    public Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient> newDecorator() {
        return this::build;
    }

    // Methods that were overridden to change the return type.

    @Override
    public RetryingRpcClientBuilder maxTotalAttempts(
            int maxTotalAttempts) {
        return (RetryingRpcClientBuilder) super.maxTotalAttempts(maxTotalAttempts);
    }

    @Override
    public RetryingRpcClientBuilder responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        return (RetryingRpcClientBuilder)
                super.responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt);
    }

    @Override
    public RetryingRpcClientBuilder responseTimeoutForEachAttempt(
            Duration responseTimeoutForEachAttempt) {
        return (RetryingRpcClientBuilder) super.responseTimeoutForEachAttempt(responseTimeoutForEachAttempt);
    }
}
