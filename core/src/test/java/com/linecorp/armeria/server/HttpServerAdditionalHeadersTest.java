package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpServerAdditionalHeadersTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/headers_merged", (ctx, req) -> {
                addBadHeaders(ctx);
                return HttpResponse.of(HttpStatus.NO_CONTENT);
            });
            sb.service("/headers_and_trailers", (ctx, req) -> {
                addBadHeaders(ctx);
                return HttpResponse.of("headers and trailers");
            });
        }

        private void addBadHeaders(ServiceRequestContext ctx) {
            ctx.addAdditionalResponseHeader(HttpHeaderNames.SCHEME, "https");
            ctx.addAdditionalResponseHeader(HttpHeaderNames.STATUS, "100");
            ctx.addAdditionalResponseHeader(HttpHeaderNames.METHOD, "CONNECT");
            ctx.addAdditionalResponseHeader(HttpHeaderNames.PATH,   "/foo");
            ctx.addAdditionalResponseTrailer(HttpHeaderNames.SCHEME, "https");
            ctx.addAdditionalResponseTrailer(HttpHeaderNames.STATUS, "100");
            ctx.addAdditionalResponseTrailer(HttpHeaderNames.METHOD, "CONNECT");
            ctx.addAdditionalResponseTrailer(HttpHeaderNames.PATH,   "/foo");
            ctx.addAdditionalResponseTrailer(HttpHeaderNames.TRANSFER_ENCODING, "magic");
        }
    };

    @Test
    void blacklistedHeadersAndTrailersMustBeFiltered() {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpResponse res = client.get("/headers_and_trailers").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.SCHEME,
                                                         HttpHeaderNames.METHOD,
                                                         HttpHeaderNames.PATH,
                                                         HttpHeaderNames.TRANSFER_ENCODING);
        assertThat(res.trailers().names()).doesNotContain(HttpHeaderNames.SCHEME,
                                                          HttpHeaderNames.STATUS,
                                                          HttpHeaderNames.METHOD,
                                                          HttpHeaderNames.PATH,
                                                          HttpHeaderNames.TRANSFER_ENCODING);
    }

    @Test
    void blacklistedHeadersAndTrailersMustBeFilteredWhenMerged() {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpResponse res = client.get("/headers_merged").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.SCHEME,
                                                         HttpHeaderNames.METHOD,
                                                         HttpHeaderNames.PATH,
                                                         HttpHeaderNames.TRANSFER_ENCODING);
    }
}
