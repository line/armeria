/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

class ServerMeterIdPrefixFunctionTest {

    @Test
    void defaultApply() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        // default
        ServerMeterIdPrefixFunction f = ServerMeterIdPrefixFunction.builder("foo")
                                                                   .build();
        MeterIdPrefix res;

        final ServiceRequestContext ctx = newServiceContext(
                HttpMethod.POST, "/post", RpcRequest.of(ServerMeterIdPrefixFunctionTest.class, "doFoo"));
        res = f.activeRequestPrefix(registry, ctx.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("service",
                                                      ServerMeterIdPrefixFunctionTest.class.getName()));

        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, ctx.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("http.status", "200"),
                                               Tag.of("method", "doFoo"),
                                               Tag.of("service",
                                                      ServerMeterIdPrefixFunctionTest.class.getName()));

        f = ServerMeterIdPrefixFunction.builder("foo")
                                       .excludeTags("service")
                                       .build();

        final ServiceRequestContext ctx2 = newServiceContext(
                HttpMethod.POST, "/post", RpcRequest.of(ServerMeterIdPrefixFunctionTest.class, "doFoo"));
        res = f.activeRequestPrefix(registry, ctx2.log().ensureRequestComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("method", "doFoo"));

        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx2.logBuilder().endResponse();
        res = f.completeRequestPrefix(registry, ctx2.log().ensureComplete());
        assertThat(res.name()).isEqualTo("foo");
        assertThat(res.tags()).containsExactly(Tag.of("hostname.pattern", "*"),
                                               Tag.of("http.status", "200"),
                                               Tag.of("method", "doFoo"));
    }

    private static ServiceRequestContext newServiceContext(HttpMethod method, String path,
                                                           @Nullable Object requestContent) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(method, path));
        ctx.logBuilder().requestContent(requestContent, null);
        ctx.logBuilder().endRequest();
        return ctx;
    }
}
