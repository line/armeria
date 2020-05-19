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
package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

class RequestScopedMdcTest {

    @BeforeEach
    void beforeEach() {
        MDC.clear();
        assertThat(MDC.getCopyOfContextMap()).isIn(Collections.emptyMap(), null);
        assertThat(RequestContext.<RequestContext>currentOrNull()).isNull();
    }

    @Test
    void threadLocalGet() {
        MDC.put("threadLocalProp", "1");
        assertThat(MDC.get("threadLocalProp")).isEqualTo("1");
    }

    @Test
    void get() {
        final ServiceRequestContext ctx = newContext();
        RequestScopedMdc.put(ctx, "foo", "1");
        assertThat(RequestScopedMdc.get(ctx, "foo")).isEqualTo("1");
        assertThat(MDC.get("foo")).isNull();

        try (SafeCloseable ignored = ctx.push()) {
            assertThat(MDC.get("foo")).isEqualTo("1");
            // Request-scoped property should have priority over thread-local one.
            MDC.put("foo", "2");
            assertThat(MDC.get("foo")).isEqualTo("1");

            // A client context should expose the properties from the root context.
            final ClientRequestContext cctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(cctx.root()).isSameAs(ctx);
            assertThat(RequestScopedMdc.get(cctx, "foo")).isEqualTo("1");

            // A client context can override the property from the root context,
            // but it shouldn't affect the root context's own property.
            RequestScopedMdc.put(cctx, "foo", "3");
            assertThat(RequestScopedMdc.get(ctx, "foo")).isEqualTo("1");
            assertThat(RequestScopedMdc.get(cctx, "foo")).isEqualTo("3");

            try (SafeCloseable ignored2 = cctx.push()) {
                // If both ctx and cctx do not have 'foo' set, thread-local property should be retrieved.
                RequestScopedMdc.remove(ctx, "foo");
                RequestScopedMdc.remove(cctx, "foo");
                assertThat(MDC.get("foo")).isEqualTo("2");
            }
        }
    }

    @Test
    void getAll() {
        final ServiceRequestContext ctx = newContext();

        MDC.put("foo", "1");
        MDC.put("bar", "2");
        RequestScopedMdc.put(ctx, "bar", "3");
        RequestScopedMdc.put(ctx, "baz", "4");

        assertThat(MDC.getCopyOfContextMap()).containsOnly(
                Maps.immutableEntry("foo", "1"),
                Maps.immutableEntry("bar", "2"));

        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(
                Maps.immutableEntry("bar", "3"),
                Maps.immutableEntry("baz", "4"));

        try (SafeCloseable ignored = ctx.push()) {
            // The case where thread-local and request-scoped maps are both non-empty.
            assertThat(MDC.getCopyOfContextMap()).containsOnly(
                    Maps.immutableEntry("foo", "1"),
                    Maps.immutableEntry("bar", "3"),
                    Maps.immutableEntry("baz", "4"));

            // The case where only request-scoped map is available.
            MDC.clear();
            assertThat(MDC.getCopyOfContextMap()).containsOnly(
                    Maps.immutableEntry("bar", "3"),
                    Maps.immutableEntry("baz", "4"));

            // The case where thread-local and request-scoped maps are both empty.
            RequestScopedMdc.clear(ctx);
            assertThat(MDC.getCopyOfContextMap()).isIn(Collections.emptyMap(), null);

            // The case where only thread-local map is available.
            MDC.put("qux", "5");
            assertThat(MDC.getCopyOfContextMap()).containsOnly(
                    Maps.immutableEntry("qux", "5"));
        }
    }

    @Test
    void getAllNested() {
        MDC.put("foo", "1");
        MDC.put("bar", "2");

        final ServiceRequestContext ctx = newContext();
        try (SafeCloseable ignored = ctx.push()) {
            final ClientRequestContext cctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

            // When the root context map exists but it's empty:
            try (SafeCloseable ignored2 = cctx.push()) {
                assertThat(RequestScopedMdc.getAll(cctx)).isEmpty();
                assertThat(MDC.getCopyOfContextMap()).containsOnly(
                        Maps.immutableEntry("foo", "1"),
                        Maps.immutableEntry("bar", "2"));
            }

            // When the root context map is not empty:
            RequestScopedMdc.put(ctx, "bar", "3");
            RequestScopedMdc.put(ctx, "baz", "4");
            try (SafeCloseable ignored2 = cctx.push()) {
                // root context's properties should be retrieved.
                assertThat(RequestScopedMdc.getAll(cctx)).containsOnly(
                        Maps.immutableEntry("bar", "3"),
                        Maps.immutableEntry("baz", "4"));
                assertThat(MDC.getCopyOfContextMap()).containsOnly(
                        Maps.immutableEntry("foo", "1"),
                        Maps.immutableEntry("bar", "3"),
                        Maps.immutableEntry("baz", "4"));

                // root context's properties should be overwritten by own properties.
                RequestScopedMdc.put(cctx, "baz", "5");
                RequestScopedMdc.put(cctx, "qux", "6");

                assertThat(RequestScopedMdc.getAll(cctx)).containsOnly(
                        Maps.immutableEntry("bar", "3"),
                        Maps.immutableEntry("baz", "5"),
                        Maps.immutableEntry("qux", "6"));
                assertThat(MDC.getCopyOfContextMap()).containsOnly(
                        Maps.immutableEntry("foo", "1"),
                        Maps.immutableEntry("bar", "3"),
                        Maps.immutableEntry("baz", "5"),
                        Maps.immutableEntry("qux", "6"));
            }
        }
    }

    @Test
    void putAll() {
        final ServiceRequestContext ctx = newContext();

        // Put an empty map.
        RequestScopedMdc.putAll(ctx, ImmutableMap.of());
        assertThat(RequestScopedMdc.getAll(ctx)).isEmpty();

        // Put a non-empty map.
        RequestScopedMdc.putAll(ctx, ImmutableMap.of("foo", "1", "bar", "2"));
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(
                Maps.immutableEntry("foo", "1"),
                Maps.immutableEntry("bar", "2"));

        // Put a non-empty map again.
        RequestScopedMdc.putAll(ctx, ImmutableMap.of("bar", "3", "baz", "4"));
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(
                Maps.immutableEntry("foo", "1"),
                Maps.immutableEntry("bar", "3"),
                Maps.immutableEntry("baz", "4"));
    }

    @Test
    void remove() {
        final ServiceRequestContext ctx = newContext();

        // Remove a non-existing entry from an empty map.
        RequestScopedMdc.remove(ctx, "foo");
        assertThat(RequestScopedMdc.getAll(ctx)).isEmpty();

        // Remove a non-existing entry from a single-entry map.
        RequestScopedMdc.put(ctx, "foo", "1");
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(Maps.immutableEntry("foo", "1"));
        RequestScopedMdc.remove(ctx, "bar");
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(Maps.immutableEntry("foo", "1"));

        // Remove an existing entry from a single-entry map.
        RequestScopedMdc.remove(ctx, "foo");
        assertThat(RequestScopedMdc.getAll(ctx)).isEmpty();

        // Remove an existing entry from a multi-entry map.
        RequestScopedMdc.put(ctx, "foo", "1");
        RequestScopedMdc.put(ctx, "bar", "2");
        RequestScopedMdc.remove(ctx, "foo");
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(Maps.immutableEntry("bar", "2"));
    }

    @Test
    void clear() {
        final ServiceRequestContext ctx = newContext();

        // Clear an empty map.
        RequestScopedMdc.clear(ctx);
        assertThat(RequestScopedMdc.getAll(ctx)).isEmpty();

        // Clear a non-empty map.
        RequestScopedMdc.put(ctx, "foo", "1");
        RequestScopedMdc.clear(ctx);
        assertThat(RequestScopedMdc.getAll(ctx)).isEmpty();
    }

    @Test
    void copy() {
        final ServiceRequestContext ctx = newContext();
        MDC.put("foo", "1");
        MDC.put("bar", "2");
        RequestScopedMdc.copy(ctx, "foo");
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(Maps.immutableEntry("foo", "1"));
    }

    @Test
    void copyAll() {
        final ServiceRequestContext ctx = newContext();
        // Copy nothing.
        RequestScopedMdc.copyAll(ctx);
        assertThat(RequestScopedMdc.getAll(ctx)).isEmpty();

        // Copy into an empty request-scoped context map.
        MDC.put("foo", "1");
        MDC.put("bar", "2");
        RequestScopedMdc.copyAll(ctx);
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(
                Maps.immutableEntry("foo", "1"),
                Maps.immutableEntry("bar", "2"));

        // Copy into a non-empty request-scoped context map.
        MDC.remove("foo");
        MDC.put("bar", "3");
        MDC.put("baz", "4");
        RequestScopedMdc.copyAll(ctx);
        assertThat(RequestScopedMdc.getAll(ctx)).containsOnly(
                Maps.immutableEntry("foo", "1"),
                Maps.immutableEntry("bar", "3"),
                Maps.immutableEntry("baz", "4"));
    }

    private static ServiceRequestContext newContext() {
        return ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
