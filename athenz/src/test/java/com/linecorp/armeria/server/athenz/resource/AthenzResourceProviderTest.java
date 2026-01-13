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

package com.linecorp.armeria.server.athenz.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonPointer;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

class AthenzResourceProviderTest {

    @Test
    public void shouldProvidePathExcludeQueryParam() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofPath(false);
        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/api/path?param=value"));

        // when
        final CompletableFuture<String> result = provider.provide(ctx, ctx.request());

        // then
        assertThat(result).isCompletedWithValue("/api/path");
    }

    @Test
    public void shouldProvidePathIncludeQueryParam() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofPath(true);
        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/api/path?param=value"));

        // when
        final CompletableFuture<String> result = provider.provide(ctx, ctx.request());

        // then
        assertThat(result).isCompletedWithValue("/api/path?param=value");
    }

    @Test
    public void shouldProvideHeaderStringIfExits() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofHeader("X-Athenz-Resource");
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/", "X-Athenz-Resource", "resource"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result).isCompletedWithValue("resource");
    }

    @Test
    public void shouldThrowExceptionIfHeaderNotExits() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofHeader("X-Athenz-Resource");
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        assertThatThrownBy(() -> provider.provide(ctx, req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void shouldThrowExceptionIfHeaderStringIsEmpty() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofHeader("X-Athenz-Resource");
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/", "X-Athenz-Resource", ""));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        assertThatThrownBy(() -> provider.provide(ctx, req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void shouldProvideJsonFieldStringIfExits() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofJsonField("/resourceId");
        final String jsonBody = "{\"resourceId\":\"resource\",\"data\":\"test\"}";
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/", "Content-Type", "application/json"),
                HttpData.ofUtf8(jsonBody));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result.join()).isEqualTo("resource");
    }

    @Test
    public void shouldProvideJsonFieldStringIfExitsByJsonPointer() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofJsonField(
                JsonPointer.compile("/resourceId"));
        final String jsonBody = "{\"resourceId\":\"resource\",\"data\":\"test\"}";
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/", "Content-Type", "application/json"),
                HttpData.ofUtf8(jsonBody));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result.join()).isEqualTo("resource");
    }

    @Test
    public void shouldThrowExceptionIfJsonFieldNotExits() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofJsonField("/resourceId");
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/", "Content-Type", "application/json"),
                HttpData.ofUtf8("{\"invalid\":\"json\"}"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThatThrownBy(result::join)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
