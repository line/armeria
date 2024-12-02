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

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

class ClientMeterIdPrefixFunctionTest {

    @Test
    void defaultApply() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        // default
        MeterIdPrefixFunction f = MeterIdPrefixFunction.builderForClient("foo")
                                                       .build();
        MeterIdPrefix res;

        final ClientRequestContext ctx = newClientContext(
                HttpMethod.POST, "/post", RpcRequest.of(ClientMeterIdPrefixFunctionTest.class, "doFoo"));
        res = f.activeRequestPrefix(registry, ctx.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("method", "doFoo"),
                                               Tag.of("service",
                                                      ClientMeterIdPrefixFunctionTest.class.getName()));

        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, ctx.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("http.status", "200"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("service",
                                                      ClientMeterIdPrefixFunctionTest.class.getName()));

        f = MeterIdPrefixFunction.builderForClient("foo")
                                 .includeTags("remoteAddress")
                                 .excludeTags("service")
                                 .build();

        final ClientRequestContext ctx2 = newClientContext(
                HttpMethod.POST, "/post", RpcRequest.of(ClientMeterIdPrefixFunctionTest.class, "doFoo"));
        res = f.activeRequestPrefix(registry, ctx2.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("method", "doFoo"),
                                               Tag.of("remoteAddress", "foo.com/1.2.3.4:8080"));

        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx2.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, ctx2.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("http.status", "200"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("remoteAddress", "foo.com/1.2.3.4:8080"));
    }

    private static ClientRequestContext newClientContext(HttpMethod method, String path,
                                                         @Nullable Object requestContent) {
        final ClientRequestContext ctx = ClientRequestContext.builder(HttpRequest.of(method, path))
                                                             .endpointGroup(Endpoint.of("foo.com", 8080)
                                                                                    .withIpAddr("1.2.3.4"))
                                                             .build();
        ctx.logBuilder().requestContent(requestContent, null);
        ctx.logBuilder().endRequest();
        return ctx;
    }
}
