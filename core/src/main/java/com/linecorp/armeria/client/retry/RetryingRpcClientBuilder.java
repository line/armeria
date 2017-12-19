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

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Builds a new {@link RetryingRpcClient} or its decorator function.
 */
public class RetryingRpcClientBuilder
        extends RetryingClientBuilder<RetryingRpcClientBuilder, RetryingRpcClient, RpcRequest, RpcResponse> {

    /**
     * Creates a new builder with the specified retry strategy.
     */
    public RetryingRpcClientBuilder(
            RetryStrategy<RpcRequest, RpcResponse> retryStrategy) {
        super(retryStrategy);
    }

    /**
     * Returns a newly-created {@link RetryingRpcClient} based on the properties of this builder.
     */
    @Override
    public RetryingRpcClient build(Client<RpcRequest, RpcResponse> delegate) {
        return new RetryingRpcClient(
                delegate, retryStrategy, defaultMaxAttempts, responseTimeoutMillisForEachAttempt);
    }

    /**
     * Returns a newly-created decorator that decorates a {@link Client} with a new {@link RetryingRpcClient}
     * based on the properties of this builder.
     */
    @Override
    public Function<Client<RpcRequest, RpcResponse>, RetryingRpcClient> newDecorator() {
        return delegate ->
                new RetryingRpcClient(
                        delegate, retryStrategy, defaultMaxAttempts, responseTimeoutMillisForEachAttempt);
    }
}
