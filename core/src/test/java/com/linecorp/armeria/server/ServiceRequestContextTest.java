/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;

import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

class ServiceRequestContextTest {

    @Test
    void current() {
        assertThatThrownBy(ServiceRequestContext::current).isInstanceOf(IllegalStateException.class)
                                                          .hasMessageContaining("unavailable");

        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable unused = sctx.push()) {
            assertThat(ServiceRequestContext.current()).isSameAs(sctx);
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable unused1 = cctx.push()) {
                assertThatUnwrapAll(ServiceRequestContext.current()).isSameAs(sctx);
                assertThatUnwrapAll(ClientRequestContext.current()).isSameAs(cctx);
                assertThatUnwrapAll((ClientRequestContext) RequestContext.current()).isSameAs(cctx);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);

        try (SafeCloseable unused = clientRequestContext().push()) {
            assertThatThrownBy(ServiceRequestContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }

    @Test
    void currentOrNull() {
        assertThat(ServiceRequestContext.currentOrNull()).isNull();

        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable unused = sctx.push()) {
            assertThat(ServiceRequestContext.currentOrNull()).isSameAs(sctx);
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable unused1 = cctx.push()) {
                assertThatUnwrapAll(ServiceRequestContext.currentOrNull()).isSameAs(sctx);
                assertThatUnwrapAll(ClientRequestContext.current()).isSameAs(cctx);
                assertThatUnwrapAll((ClientRequestContext) RequestContext.current()).isSameAs(cctx);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);

        try (SafeCloseable unused = clientRequestContext().push()) {
            assertThat(ServiceRequestContext.currentOrNull()).isNull();
        }
    }

    @Test
    void mapCurrent() {
        assertThat(ServiceRequestContext.mapCurrent(ctx -> "foo", () -> "defaultValue"))
                .isEqualTo("defaultValue");
        assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isNull();

        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable unused = sctx.push()) {
            assertThat(ServiceRequestContext.mapCurrent(c -> c == sctx ? "foo" : "bar",
                                                        () -> "defaultValue"))
                    .isEqualTo("foo");
            assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isSameAs(sctx);
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable unused1 = cctx.push()) {
                assertThat(ServiceRequestContext.mapCurrent(c -> c == sctx ? "foo" : "bar",
                                                            () -> "defaultValue"))
                        .isEqualTo("foo");
                assertThat(ClientRequestContext.mapCurrent(c -> c.unwrapAll() == cctx ? "baz" : "qux",
                                                           () -> "defaultValue"))
                        .isEqualTo("baz");
                assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isSameAs(sctx);
                assertThatUnwrapAll(ClientRequestContext.mapCurrent(Function.identity(), null)).isSameAs(cctx);
                assertThatUnwrapAll(RequestContext.mapCurrent(Function.identity(), null)).isSameAs(cctx);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);

        try (SafeCloseable unused = clientRequestContext().push()) {
            assertThatThrownBy(() -> ServiceRequestContext.mapCurrent(c -> "foo", () -> "bar"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }

    @Test
    void pushReentrance() {
        final ServiceRequestContext ctx = serviceRequestContext();
        try (SafeCloseable ignored = ctx.push()) {
            assertUnwrapAllCurrentCtx(ctx);
            try (SafeCloseable ignored2 = ctx.push()) {
                assertUnwrapAllCurrentCtx(ctx);
            }
            assertUnwrapAllCurrentCtx(ctx);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsThisServiceCtx() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertUnwrapAllCurrentCtx(sctx);
            // The root of ClientRequestContext is sctx.
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable ignored1 = cctx.push()) {
                assertUnwrapAllCurrentCtx(cctx);
                try (SafeCloseable ignored2 = sctx.push()) {
                    assertUnwrapAllCurrentCtx(sctx);
                }
                assertUnwrapAllCurrentCtx(cctx);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldIrrelevantClientCtx() {
        final ClientRequestContext cctx = clientRequestContext();
        try (SafeCloseable ignored = cctx.push()) {
            assertUnwrapAllCurrentCtx(cctx);
            final ServiceRequestContext sctx = serviceRequestContext();
            assertThatThrownBy(sctx::push).isInstanceOf(IllegalStateException.class);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldIrrelevantServiceCtx() {
        final ServiceRequestContext sctx1 = serviceRequestContext();
        final ServiceRequestContext sctx2 = serviceRequestContext();
        try (SafeCloseable ignored = sctx1.push()) {
            assertUnwrapAllCurrentCtx(sctx1);
            assertThatThrownBy(sctx2::push).isInstanceOf(IllegalStateException.class);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void queryParams() {
        final String path = "/foo";
        final QueryParams queryParams = QueryParams.of("param1", "value1",
                                                       "param1", "value2",
                                                       "Param1", "Value3",
                                                       "PARAM1", "VALUE4");
        final String pathAndQuery = path + '?' + queryParams.toQueryString();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET,
                                                                                  pathAndQuery));

        assertThat(ctx.queryParams()).isEqualTo(queryParams);

        assertThat(ctx.queryParam("param1")).isEqualTo("value1");
        assertThat(ctx.queryParam("Param1")).isEqualTo("Value3");
        assertThat(ctx.queryParam("PARAM1")).isEqualTo("VALUE4");
        assertThat(ctx.queryParam("Not exist")).isNull();

        assertThat(ctx.queryParams("param1")).isEqualTo(ImmutableList.of("value1", "value2"));
        assertThat(ctx.queryParams("Param1")).isEqualTo(ImmutableList.of("Value3"));
        assertThat(ctx.queryParams("PARAM1")).isEqualTo(ImmutableList.of("VALUE4"));
        assertThat(ctx.queryParams("Not exist")).isEmpty();
    }

    @Test
    void defaultServiceRequestContextShouldLogExceptions() {
        final ServiceRequestContext sctx = serviceRequestContext();
        assertThat(sctx.shouldReportUnloggedExceptions()).isTrue();
    }

    private static void assertUnwrapAllCurrentCtx(@Nullable RequestContext ctx) {
        final RequestContext current = RequestContext.currentOrNull();
        if (current == null) {
            assertThat(ctx).isNull();
        } else {
            assertThatUnwrapAll(current).isEqualTo(ctx);
        }
    }

    private static <T extends RequestContext> ObjectAssert<RequestContext> assertThatUnwrapAll(T actual) {
        return assertThat(actual.unwrapAll());
    }

    private static ServiceRequestContext serviceRequestContext() {
        return ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }

    private static ClientRequestContext clientRequestContext() {
        return ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
