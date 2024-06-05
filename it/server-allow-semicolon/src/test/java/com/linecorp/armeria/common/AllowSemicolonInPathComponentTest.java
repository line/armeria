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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@SetSystemProperty(key = "com.linecorp.armeria.allowSemicolonInPathComponent", value = "true")
class AllowSemicolonInPathComponentTest {

    @Nullable
    private static volatile Boolean lastRouteWasFallback;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, world!"));
            sb.decorator((delegate, ctx, req) -> {
                lastRouteWasFallback = ctx.config().route().isFallback();
                return delegate.serve(ctx, req)
                               // Make sure that FallbackService does not throw an exception
                               .mapHeaders(headers -> headers.toBuilder().set("x-trace-id", "foo").build());
            });
        }
    };

    @Test
    void fallbackServiceShouldNotRaiseExceptionWhenPathContainsSemicolon() {
        // See https://github.com/line/armeria/issues/5726 for more information.
        final AggregatedHttpResponse res = server.blockingWebClient().get("/;");
        assertThat(res.status()).isSameAs(HttpStatus.NOT_FOUND);
        assertThat(res.headers().get("x-trace-id")).isEqualTo("foo");
        assertThat(lastRouteWasFallback).isTrue();
    }
}
