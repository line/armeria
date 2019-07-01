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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;

class ServiceRequestContextTest {

    private static final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");

    @Test
    void current() {
        assertThatThrownBy(ServiceRequestContext::current).isInstanceOf(IllegalStateException.class)
                                                          .hasMessageContaining("unavailable");

        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ServiceRequestContext.current()).isSameAs(ctx);
        }

        try (SafeCloseable unused = ClientRequestContext.of(req).push()) {
            assertThatThrownBy(ServiceRequestContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }

    @Test
    void currentOrNull() {
        assertThat(ServiceRequestContext.currentOrNull()).isNull();

        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ServiceRequestContext.currentOrNull()).isSameAs(ctx);
        }

        try (SafeCloseable unused = ClientRequestContext.of(req).push()) {
            assertThatThrownBy(ServiceRequestContext::currentOrNull)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }

    @Test
    void mapCurrent() {
        assertThat(ServiceRequestContext.mapCurrent(ctx -> "foo", () -> "bar")).isEqualTo("bar");
        assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isNull();

        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ServiceRequestContext.mapCurrent(c -> "foo", () -> "bar")).isEqualTo("foo");
            assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isSameAs(ctx);
        }

        try (SafeCloseable unused = ClientRequestContext.of(req).push()) {
            assertThatThrownBy(() -> ServiceRequestContext.mapCurrent(c -> "foo", () -> "bar"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }
}
