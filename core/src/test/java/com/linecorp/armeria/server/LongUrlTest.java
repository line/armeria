/*
 * Copyright 2022 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http2.Http2Exception.HeaderListSizeException;

class LongUrlTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http1MaxInitialLineLength(200);
            sb.http2MaxHeaderListSize(200);

            sb.service("/", (ctx, req) -> {
                return HttpResponse.of("OK");
            });
        }
    };

    @Test
    void shouldReturn414ForLongUrlForHTTP1() {
        final BlockingWebClient client = BlockingWebClient.of(server.uri(SessionProtocol.H1C));
        final AggregatedHttpResponse response = client.get("/?q" + Strings.repeat("a", 200));
        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_URI_TOO_LONG);
    }

    @Test
    void shouldThrowHeaderListSizeExceptionForLongUrlForHTTP2() {
        final BlockingWebClient client = BlockingWebClient.of(server.uri(SessionProtocol.H2C));
        assertThatThrownBy(() -> {
            client.get("/?q" + Strings.repeat("a", 200));
        }).isInstanceOf(UnprocessedRequestException.class)
          .hasCauseInstanceOf(HeaderListSizeException.class)
          .hasMessageContaining("Header size exceeded max allowed size (200)");
    }
}
