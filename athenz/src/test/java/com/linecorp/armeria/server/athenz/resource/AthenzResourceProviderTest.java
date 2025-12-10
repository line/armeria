package com.linecorp.armeria.server.athenz.resource;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonPointer;

public class AthenzResourceProviderTest {

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
    public void shouldProvideEmptyStringIfHeaderNotExits() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofHeader("X-Athenz-Resource");
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result).isCompletedWithValue("");
    }

    @Test
    public void shouldProvideJsonFieldStringIfExits() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofJsonField("resourceId");
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
    public void shouldProvideEmptyStringIfJsonFieldNotExits() {
        // given
        final AthenzResourceProvider provider = AthenzResourceProvider.ofJsonField("resourceId");
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/", "Content-Type", "application/json"),
                HttpData.ofUtf8("{invalid json}"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result.join()).isEmpty();
    }
}
