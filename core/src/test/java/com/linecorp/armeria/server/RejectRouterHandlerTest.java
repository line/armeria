/*
 * Copyright 2025 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;

class RejectRouterHandlerTest {

    @Test
    void when_duplicate_route_exists_then_server_builder_should_ignore_or_warn() {
        final List<RejectedRouteHandler> handlers = ImmutableList.of(
                RejectedRouteHandler.DISABLED,
                RejectedRouteHandler.WARN
        );

        for (RejectedRouteHandler handler : handlers) {
            assertThatCode(
                    () -> duplicateRouteServerBuilder()
                            .rejectedRouteHandler(handler)
                            .build()
            ).doesNotThrowAnyException();
        }
    }

    @Test
    void when_duplicate_route_exists_then_server_builder_should_throw_error() {
        assertThatThrownBy(
                () -> duplicateRouteServerBuilder()
                        .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                        .build()
        ).isInstanceOf(DuplicateRouteException.class);
    }

    @Test
    void when_duplicate_route_exists_then_virtual_host_builder_should_ignore_or_warn() {
        final List<RejectedRouteHandler> handlers = ImmutableList.of(
                RejectedRouteHandler.DISABLED,
                RejectedRouteHandler.WARN
        );

        for (RejectedRouteHandler handler : handlers) {
            assertThatCode(
                    () -> duplicatedRouteVirtualHostBuilder()
                            .rejectedRouteHandler(handler)
                            .and()
                            .build()
            ).doesNotThrowAnyException();
        }
    }

    @Test
    void when_duplicate_route_exists_then_virtual_host_builder_should_throw_error() {
        assertThatThrownBy(
                () -> duplicatedRouteVirtualHostBuilder()
                        .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                        .and()
                        .build()
        ).isInstanceOf(DuplicateRouteException.class);
    }

    @Test
    void fail_route_handler_do_not_consider_decorator_in_same_path() {
        assertThatCode(() -> serverBuilderWithDecorator()
                .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                .build()).doesNotThrowAnyException();

        assertThatCode(() -> virtualServerBuilderWithDecorator()
                .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                .and()
                .build()).doesNotThrowAnyException();
    }

    private VirtualHostBuilder duplicatedRouteVirtualHostBuilder() {
        return Server.builder()
                     .virtualHost("foo.com")
                     .service("/foo", (ctx, req) -> HttpResponse.of("ok"))
                     .service("/foo", (ctx, req) -> HttpResponse.of("duplicate"));
    }

    private VirtualHostBuilder virtualServerBuilderWithDecorator() {
        return Server.builder()
                     .virtualHost("foo.com")
                     .service("/foo", (ctx, req) -> HttpResponse.of("ok"))
                     .decorator("/foo", (delegate, ctx, req) -> HttpResponse.of("duplicate"));
    }

    private ServerBuilder duplicateRouteServerBuilder() {
        return Server.builder()
                     .service("/foo", (ctx, req) -> HttpResponse.of("ok"))
                     .service("/foo", (ctx, req) -> HttpResponse.of("duplicate"));
    }

    private ServerBuilder serverBuilderWithDecorator() {
        return Server.builder()
                     .service("/foo", (ctx, req) -> HttpResponse.of("ok"))
                     .decorator("/foo", (delegate, ctx, req) -> HttpResponse.of("duplicate"));
    }
}
