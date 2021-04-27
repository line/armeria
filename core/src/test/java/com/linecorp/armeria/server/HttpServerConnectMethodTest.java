/*
 * Copyright 2021 LINE Corporation
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
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerConnectMethodTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void connectMethodDisallowedInHttp1() {
        final WebClient client = WebClient.of(server.uri(SessionProtocol.H1C));
        final AggregatedHttpResponse res1 = client
                .execute(HttpRequest.of(HttpMethod.CONNECT, "/"))
                .aggregate()
                .join();
        assertThat(res1.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);

        final AggregatedHttpResponse res2 = client
                .prepare()
                .method(HttpMethod.CONNECT)
                .path("/")
                .header(HttpHeaderNames.PROTOCOL, "websocket")
                .execute()
                .aggregate()
                .join();

        // HTTP/1 decoder will reject a header starts with `:` anyway.
        assertThat(res2.status()).isSameAs(HttpStatus.BAD_REQUEST);
    }

    @Test
    void connectMethodDisallowedInHttp2() {
        final WebClient client = WebClient.of(server.uri(SessionProtocol.H2C));
        final AggregatedHttpResponse res1 = client
                .execute(HttpRequest.of(HttpMethod.CONNECT, "/"))
                .aggregate()
                .join();
        assertThat(res1.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);

        // TODO(trustin): Uncomment this test once Netty accepts the `:protocol` pseudo header.
        //                https://github.com/netty/netty/pull/11192
        // final AggregatedHttpResponse res2 = client
        //         .prepare()
        //         .method(HttpMethod.CONNECT)
        //         .path("/")
        //         .header(HttpHeaderNames.PROTOCOL, "websocket")
        //         .execute()
        //         .aggregate()
        //         .join();
        // assertThat(res2.status()).isSameAs(HttpStatus.OK);
    }
}
