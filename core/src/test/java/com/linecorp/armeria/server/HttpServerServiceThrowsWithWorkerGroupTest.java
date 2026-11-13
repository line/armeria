/*
 * Copyright 2026 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerServiceThrowsWithWorkerGroupTest {

    // Regression test for https://github.com/line/armeria/issues/7061
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceWorkerGroup(1);
            sb.service("/throw", (ctx, req) -> {
                throw new AnticipatedException("Service failed");
            });
        }
    };

    @Test
    void shouldReturn500WithoutResettingStreamWhenServiceThrows() {
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .responseTimeoutMillis(5000)
                                          .build();

        for (int i = 0; i < 2000; i++) {
            final HttpRequestWriter req = HttpRequest.streaming(RequestHeaders.of(HttpMethod.POST, "/throw"));
            final HttpResponse res = client.execute(req);
            // Ignore the result because the 500 response can close the stream before the body is written.
            final boolean unused = req.tryWrite(HttpData.ofUtf8("data"));
            req.close();

            final AggregatedHttpResponse agg;
            try {
                agg = res.aggregate().join();
            } catch (Throwable t) {
                throw new AssertionError("Request failed at iteration " + i, t);
            }
            assertThat(agg.status()).describedAs("iteration %d", i)
                                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

