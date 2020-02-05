/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.armeria.common.HttpHeaderNames.LOCATION;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpClientRequestPathTest {

    @RegisterExtension
    @Order(10)
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/new-location", (ctx, req) -> HttpResponse.of(OK));
        }
    };

    @RegisterExtension
    @Order(20)
    static final ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/simple-client", (ctx, req) -> HttpResponse.of(OK))
              .service("/redirect", (ctx, req) -> {
                  final HttpHeaders headers = ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                                 LOCATION, server1.httpUri() + "/new-location");
                  return HttpResponse.of(headers);
              });
        }
    };

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, mode = Mode.EXCLUDE, names = "UNKNOWN")
    void default_withAbsolutePath(HttpMethod method) {
        final HttpRequest request = HttpRequest.of(method, server2.httpUri() + "/simple-client");
        final HttpResponse response = WebClient.of().execute(request);
        assertThat(response.aggregate().join().status()).isEqualTo(OK);
    }

    @Test
    void default_withRelativePath() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/simple-client");
        final HttpResponse response = WebClient.of().execute(request);
        assertThatThrownBy(() -> response.aggregate().join())
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no authority");
    }

    @Test
    void custom_withAbsolutePath() {
        final WebClient client = WebClient.of(server1.httpUri());
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, server2.httpUri() + "/simple-client");
        final HttpResponse response = client.execute(request);
        assertThat(response.aggregate().join().status()).isEqualTo(OK);
    }

    @Test
    void custom_withRelativePath() {
        final WebClient client = WebClient.of(server2.httpUri());
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/simple-client");
        final HttpResponse response = client.execute(request);
        assertThat(response.aggregate().join().status()).isEqualTo(OK);
    }

    @Test
    void redirect() {
        final WebClient client = WebClient.of(server2.httpUri());
        final AggregatedHttpResponse redirected = client.get("/redirect").aggregate().join();
        final String location = redirected.headers().get(LOCATION);
        assertThat(location).isNotNull();
        final AggregatedHttpResponse actual = client.get(location).aggregate().join();
        assertThat(actual.status()).isEqualTo(OK);
    }
}
