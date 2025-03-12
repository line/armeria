package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http2.Http2Exception.HeaderListSizeException;

class HeaderListSizeExceptionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.delayed(
                    () -> HttpResponse.of("OK"), Duration.ofMillis(100)));
        }
    };


    @Test
    void doNotSendRstStreamWhenHeaderListSizeExceptionIsRaised() throws InterruptedException {
        final CompletableFuture<AggregatedHttpResponse> future = server.webClient().get("/").aggregate();
        final String a = Strings.repeat("aa", 10000);
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/", "foo", "bar",
                                                         "baz", a);
        assertThatThrownBy(() -> server.webClient().execute(headers).aggregate().join())
                .hasCauseInstanceOf(UnprocessedRequestException.class)
                .cause()
                .hasCauseInstanceOf(HeaderListSizeException.class);
        // If the client sends RST_STREAM with invalid stream ID, the server will send GOAWAY back thus
        // the first request will be failed with ClosedSessionException.
        assertThat(future.join().status()).isSameAs(HttpStatus.OK);
    }
}
