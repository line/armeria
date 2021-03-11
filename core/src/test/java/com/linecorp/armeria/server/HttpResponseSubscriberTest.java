/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class HttpResponseSubscriberTest {

    private static final AtomicBoolean completed = new AtomicBoolean();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.NO_CONTENT).contentType(
                        MediaType.PLAIN_TEXT_UTF_8).build();
                final HttpResponseWriter streaming = HttpResponse.streaming();
                streaming.write(headers);
                streaming.write(HttpData.ofUtf8("foo"));
                streaming.close();
                return streaming;
            });

            sb.service("/trailers", (ctx, req) -> {
                final HttpResponseWriter streaming = HttpResponse.streaming();
                streaming.write(ResponseHeaders.of(HttpStatus.OK));
                streaming.write(HttpData.ofUtf8("foo"));
                streaming.write(HttpHeaders.of("status", "0"));
                streaming.close();
                streaming.whenComplete().handle((unused, cause) -> {
                    completed.set(cause == null);
                    return null;
                });
                return streaming;
            });
        }
    };

    @Test
    void httpResponseSubscriberDoesNotThrowExceptionWhenContentIsNotEmpty() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.content().isEmpty()).isTrue();
    }

    @Test
    void shouldNotCancelWhenFullyConsumed() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/trailers").aggregate().join();
        assertThat(res.trailers().get("status")).isEqualTo("0");
        await().untilTrue(completed);
    }
}
