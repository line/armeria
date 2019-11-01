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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

class ClientRequestContextTest {

    private static final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");

    @Test
    void current() {
        assertThatThrownBy(ClientRequestContext::current).isInstanceOf(IllegalStateException.class)
                                                         .hasMessageContaining("unavailable");

        final ClientRequestContext ctx = ClientRequestContext.of(req);
        assertThat(ctx.uuid()).isNotNull();
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ClientRequestContext.current()).isSameAs(ctx);
        }

        try (SafeCloseable unused = ServiceRequestContext.of(req).push()) {
            assertThatThrownBy(ClientRequestContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a client-side context");
        }
    }

    @Test
    void currentOrNull() {
        assertThat(ClientRequestContext.currentOrNull()).isNull();

        final ClientRequestContext ctx = ClientRequestContext.of(req);
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ClientRequestContext.currentOrNull()).isSameAs(ctx);
        }

        try (SafeCloseable unused = ServiceRequestContext.of(req).push()) {
            assertThatThrownBy(ClientRequestContext::currentOrNull)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a client-side context");
        }
    }

    @Test
    void mapCurrent() {
        assertThat(ClientRequestContext.mapCurrent(ctx -> "foo", () -> "bar")).isEqualTo("bar");
        assertThat(ClientRequestContext.mapCurrent(Function.identity(), null)).isNull();

        final ClientRequestContext ctx = ClientRequestContext.of(req);
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ClientRequestContext.mapCurrent(c -> "foo", () -> "bar")).isEqualTo("foo");
            assertThat(ClientRequestContext.mapCurrent(Function.identity(), null)).isSameAs(ctx);
        }

        try (SafeCloseable unused = ServiceRequestContext.of(req).push()) {
            assertThatThrownBy(() -> ClientRequestContext.mapCurrent(c -> "foo", () -> "bar"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a client-side context");
        }
    }
}
