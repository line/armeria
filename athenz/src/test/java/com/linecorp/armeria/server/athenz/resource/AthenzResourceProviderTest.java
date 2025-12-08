package com.linecorp.armeria.server.athenz.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

public class AthenzResourceProviderTest {

    @Test
    public void shouldProvidePath() {
        // given
        final PathAthenzResourceProvider provider = new PathAthenzResourceProvider();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/api/path"));

        // when
        final CompletableFuture<String> result = provider.provide(ctx, ctx.request());

        // then
        assertThat(result).isCompletedWithValue("/api/path");
    }

    @Test
    public void shouldProvideHeaderStringIfExits() {
        // given
        final HeaderAthenzResourceProvider provider = new HeaderAthenzResourceProvider("X-Athenz-Resource");
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/", "X-Athenz-Resource", "resource"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result).isCompletedWithValue("resource");
    }

    @Test
    public void shouldProvideEmptyStringIfHeaderNotExits() {
        // given
        final HeaderAthenzResourceProvider provider = new HeaderAthenzResourceProvider("X-Athenz-Resource");
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
        final ObjectMapper mapper = new ObjectMapper();
        final JsonBodyFieldAthenzResourceProvider provider = new JsonBodyFieldAthenzResourceProvider(mapper, "resourceId");
        final String jsonBody = "{\"resourceId\":\"resource\",\"data\":\"test\"}";
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/", "Content-Type", "application/json"), HttpData.ofUtf8(jsonBody));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result.join()).isEqualTo("resource");
    }

    @Test
    public void shouldProvideEmptyStringIfJsonFieldNotExits() {
        // given
        final ObjectMapper mapper = new ObjectMapper();
        final JsonBodyFieldAthenzResourceProvider provider = new JsonBodyFieldAthenzResourceProvider(mapper, "resourceId");
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/", "Content-Type", "application/json"), HttpData.ofUtf8("{invalid json}"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // when
        final CompletableFuture<String> result = provider.provide(ctx, req);

        // then
        assertThat(result.join()).isEmpty();
    }
}
