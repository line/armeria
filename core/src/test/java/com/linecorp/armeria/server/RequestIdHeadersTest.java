/*
 * Copyright 2023 LINE Corporation
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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class RequestIdHeadersTest {

    private static final String REQUEST_ID_KEY = "X-RequestId";
    private static final String REQUEST_ID_VAL = "123";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/",
                       (ctx, req) -> HttpResponse.of(200))
              .requestIdGenerator(
                      ctx -> {
                          final String requestIdVal = ctx.headers().get(REQUEST_ID_KEY);
                          if (REQUEST_ID_VAL.equals(requestIdVal)) {
                              return RequestId.of(Long.parseLong(requestIdVal));
                          } else {
                              throw new RuntimeException("raise_exception");
                          }
                      }
              );
        }
    };

    @Test
    void shouldReturnRequestIdGeneratedFromHeaders() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient();
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();

        final AggregatedHttpResponse response = client.prepare()
                                                      .get("/")
                                                      .header(REQUEST_ID_KEY, REQUEST_ID_VAL)
                                                      .execute();

        assertThat(captor.size()).isOne();

        final ServiceRequestContext capturedContext = captor.take();
        final RequestId expected = RequestId.of(123L);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(capturedContext.id().text()).isEqualTo(expected.text());
        assertThat(capturedContext.id().shortText()).isEqualTo(expected.shortText());
    }

    @Test
    void shouldReturnRandomRequestIdWhenExceptionThrown() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient();
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();

        final AggregatedHttpResponse response = client.prepare()
                                                      .get("/")
                                                      .execute();

        assertThat(captor.size()).isOne();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(captor.take().id().shortText()).hasSize(8);
    }
}
