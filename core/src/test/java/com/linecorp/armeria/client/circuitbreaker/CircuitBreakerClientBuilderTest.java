/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SuccessFunction;

class CircuitBreakerClientBuilderTest {

    @Test
    void buildWithMaxContentLength() {
        final CircuitBreakerRuleWithContent<HttpResponse> rule =
                CircuitBreakerRuleWithContent.onResponse((unused1, unused2) -> null);
        assertThatThrownBy(() -> CircuitBreakerClient.builder(rule, 0))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("maxContentLength: 0 (expected: > 0)");

        CircuitBreakerClient.builder(rule, 1);
    }

    @Test
    void successFunctionOverridesUserRule_whenEnabled() {
        final CircuitBreakerRule userRule = failOnSuccessStatus();
        final CircuitBreakerClient client = CircuitBreakerClient.builder(userRule)
                                                                .useSuccessFunction(true)
                                                                .build(mock(HttpClient.class));
        final ClientRequestContext ctx = httpCtxWith(SuccessFunction.always());
        assertThat(client.rule().shouldReportAsSuccess(ctx, null).toCompletableFuture().join())
                .isEqualTo(CircuitBreakerDecision.success());
    }

    @Test
    void successFunctionIgnored_whenDisabled() {
        final CircuitBreakerRule userRule = failOnSuccessStatus();
        final CircuitBreakerClient client = CircuitBreakerClient.builder(userRule)
                                                                .build(mock(HttpClient.class));
        final ClientRequestContext ctx = httpCtxWith(SuccessFunction.always());
        assertThat(client.rule().shouldReportAsSuccess(ctx, null).toCompletableFuture().join())
                .isEqualTo(CircuitBreakerDecision.failure());
    }

    @Test
    void successFunctionOverridesRpcRule_whenEnabled() {
        final CircuitBreakerRuleWithContent<RpcResponse> userRule =
                CircuitBreakerRuleWithContent.<RpcResponse>builder()
                                             .onStatusClass(HttpStatusClass.SUCCESS)
                                             .thenFailure();
        final CircuitBreakerRpcClient client = CircuitBreakerRpcClient.builder(userRule)
                                                                      .useSuccessFunction(true)
                                                                      .build(mock(RpcClient.class));
        final ClientRequestContext ctx = rpcCtxWith(SuccessFunction.always());
        assertThat(client.ruleWithContent().shouldReportAsSuccess(ctx, null, null)
                          .toCompletableFuture().join())
                .isEqualTo(CircuitBreakerDecision.success());
    }

    private static CircuitBreakerRule failOnSuccessStatus() {
        return CircuitBreakerRule.builder()
                                 .onStatusClass(HttpStatusClass.SUCCESS)
                                 .thenFailure();
    }

    private static ClientRequestContext httpCtxWith(SuccessFunction successFunction) {
        final ClientOptions opts = ClientOptions.builder()
                                                .successFunction(successFunction)
                                                .build();
        final ClientRequestContext ctx =
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                    .options(opts)
                                    .build();
        completeLog(ctx);
        return ctx;
    }

    private static ClientRequestContext rpcCtxWith(SuccessFunction successFunction) {
        final ClientOptions opts = ClientOptions.builder()
                                                .successFunction(successFunction)
                                                .build();
        final ClientRequestContext ctx =
                ClientRequestContext.builder(RpcRequest.of(Object.class, "test"), "h2c://dummy/")
                                    .options(opts)
                                    .build();
        completeLog(ctx);
        return ctx;
    }

    private static void completeLog(ClientRequestContext ctx) {
        ctx.logBuilder().endRequest();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().endResponse();
    }
}
