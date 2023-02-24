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
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class RequestIdHeadersTest {

    static final String REQUEST_ID_KEY = "X-RequestId";
    static final String REQUEST_ID_VAL = "From_Client";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestIdGenerator((ctx) -> {
                  final String header = ctx.headers().get(REQUEST_ID_KEY, "default");

                  if (header.equals(REQUEST_ID_VAL)) {
                      return RequestId.of(123L);
                  }

                  return RequestId.random();
              })
              .service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void shouldReturnRequestIdGeneratedFromHeaders() throws InterruptedException {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();

        client.prepare()
              .get("/")
              .header(REQUEST_ID_KEY, REQUEST_ID_VAL)
              .execute();

        assertThat(captor.size()).isEqualTo(1);

        final ServiceRequestContext capturedContext = captor.take();
        final RequestId expected = RequestId.of(123L);

        assertThat(capturedContext.id().text()).isEqualTo(expected.text());
        assertThat(capturedContext.id().shortText()).isEqualTo(expected.shortText());
    }
}
