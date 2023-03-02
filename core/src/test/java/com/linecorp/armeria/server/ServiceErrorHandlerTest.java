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

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServiceErrorHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final HttpService service = (ctx, req) -> {
                throw new RuntimeException("foo");
            };
            sb.route()
              .get("/foo")
              .serviceErrorHandler((ctx, cause) -> HttpResponse.of(HttpStatus.BAD_REQUEST))
              .build(service);
        }
    };

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void onServiceExceptionTest(SessionProtocol protocol) {
        final WebClient client = WebClient.of(server.uri(protocol));
        final AggregatedHttpResponse res1 = client.get("/foo")
                                                  .aggregate().join();

        assertThat(res1.status()).isSameAs(HttpStatus.BAD_REQUEST);
    }
}
