package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class FallbackServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> HttpResponse.of("OK"));
            sb.decorator((delegate, ctx, req) -> {
                sctx = ctx;
                return delegate.serve(ctx, req);
            });
        }
    };

    @Nullable
    static ServiceRequestContext sctx;

    @Test
    void isFallbackRoute() {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res = client.prepare()
                                           .get("/fallback")
                                           .execute()
                                           .aggregate().join();
        assertThat(res.status().code()).isEqualTo(404);
        assertThat(sctx.config().route().isFallback()).isTrue();

        res = client.prepare()
                    .get("/foo")
                    .execute()
                    .aggregate().join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(sctx.config().route().isFallback()).isFalse();
    }
}
