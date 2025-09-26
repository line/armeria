/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryDecision;
import com.linecorp.armeria.client.retry.RetryLimitedException;
import com.linecorp.armeria.client.retry.RetryLimiter;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.RpcResponse;

import testing.thrift.main.HelloService;

class RpcRetryLimiterTest {

    @Test
    void basicCase() throws Exception {
        final Backoff fixed = Backoff.fixed(0);
        // simulates grpc retry throttling behavior
        final RetryRule retryRule = RetryRule.builder()
                                             .onException()
                                             .build(RetryDecision.retry(fixed));
        final RetryConfig<RpcResponse> config = RetryConfig.builderForRpc(retryRule)
                                                           .retryLimiter(RetryLimiter.tokenBased(3, 1))
                                                           .build();

        final AtomicInteger counter = new AtomicInteger();
        final HelloService.Iface iface = ThriftClients.builder("http://foo.com")
                                                      .rpcDecorator((delegate, ctx, req) -> {
                                                          counter.incrementAndGet();
                                                          return RpcResponse.ofFailure(new RuntimeException());
                                                      })
                                                      .rpcDecorator(RetryingRpcClient.newDecorator(config))
                                                      .build(HelloService.Iface.class);
        assertThatThrownBy(() -> iface.hello("hello")).isInstanceOf(UnprocessedRequestException.class)
                                                      .hasCauseInstanceOf(RetryLimitedException.class);
        assertThat(counter).hasValue(2);
    }
}
