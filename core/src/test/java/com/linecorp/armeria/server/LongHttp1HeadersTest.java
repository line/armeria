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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class LongHttp1HeadersTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http1MaxHeaderSize(100);

            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @Test
    void shouldReturn431WithLongHeadersForHttp1() {
        final BlockingWebClient client = BlockingWebClient.of(server.uri(SessionProtocol.H1C));
        final AggregatedHttpResponse response = client.prepare()
                                                      .get("/")
                                                      .header("x-long", Strings.repeat("1", 100))
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
    }
}
