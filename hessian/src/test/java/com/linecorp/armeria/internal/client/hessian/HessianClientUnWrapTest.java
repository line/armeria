/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.client.hessian;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRpcClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.hessian.service.HelloService;

/**
 * test decorator.
 *
 * @author eisig
 */
class HessianClientUnWrapTest {

    @Test
    void test() {
        final  HelloService client = Clients.builder("hessian+http://127.0.0.1:1/").decorator(
                                             LoggingClient.newDecorator())
                                     .rpcDecorator(RetryingRpcClient.newDecorator(
                                             RetryRuleWithContent.<RpcResponse>builder().thenNoRetry()))
                                     .build(HelloService.class);

        assertThat(Clients.unwrap(client, HelloService.class)).isSameAs(client);

        assertThat(Clients.unwrap(client, RetryingRpcClient.class)).isInstanceOf(RetryingRpcClient.class);
        assertThat(Clients.unwrap(client, LoggingClient.class)).isInstanceOf(LoggingClient.class);

        assertThat(Clients.unwrap(client, Unwrappable.class)).isInstanceOf(
                HessianHttpClientInvocationHandler.class);
        assertThat(Clients.unwrap(client, Client.class)).isInstanceOf(RetryingRpcClient.class);
        assertThat(Clients.unwrap(client, CircuitBreakerRpcClient.class)).isNull();
    }
}
