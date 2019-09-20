package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpClientAdditionalHeadersTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(req.headers().toString()));
        }
    };

    @Test
    void blacklistedHeadersMustBeFiltered() {
        final HttpClient client = new HttpClientBuilder(server.httpUri("/"))
                .decorator((delegate, ctx, req) -> {
                    ctx.addAdditionalRequestHeader(HttpHeaderNames.SCHEME, "https");
                    ctx.addAdditionalRequestHeader(HttpHeaderNames.STATUS, "503");
                    ctx.addAdditionalRequestHeader(HttpHeaderNames.METHOD, "CONNECT");
                    ctx.addAdditionalRequestHeader("foo", "bar");
                    return delegate.execute(ctx, req);
                })
                .build();

        assertThat(client.get("/").aggregate().join().contentUtf8())
                .doesNotContain("=https")
                .doesNotContain("=503")
                .doesNotContain("=CONNECT")
                .contains("foo=bar");
    }
}
