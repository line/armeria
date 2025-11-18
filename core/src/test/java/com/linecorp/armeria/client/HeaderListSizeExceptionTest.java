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
