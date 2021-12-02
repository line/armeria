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

package com.linecorp.armeria.client.cookie;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class CookieClientTest {

    private static int port;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.virtualHost("foo.com")
              .service("/set-cookie", (ctx, req) -> {
                  // Set false to "secure" attribute to use HTTP.
                  final String cookie1 = Cookie.secureBuilder("some-cookie", "foo").path("/").secure(false)
                                               .build().toSetCookieHeader();
                  final String cookie2 = Cookie.secureBuilder("some-cookie2", "bar").path("/").secure(false)
                                               .build().toSetCookieHeader();
                  final String cookie3 = Cookie.secureBuilder("bad-cookie", "hmm").path("/").domain("bar.com")
                                               .secure(false).build().toSetCookieHeader();
                  final HttpHeaders headers = HttpHeaders.builder()
                                                         .add(HttpHeaderNames.SET_COOKIE, cookie1)
                                                         .add(HttpHeaderNames.SET_COOKIE, cookie2)
                                                         .add(HttpHeaderNames.SET_COOKIE, cookie3)
                                                         .build();
                  return HttpResponse.of(ResponseHeaders.builder().status(HttpStatus.OK).add(headers).build());
              })
              .service("/get-cookie", (ctx, req) -> {
                  final String cookie = req.headers().get(HttpHeaderNames.COOKIE);
                  return HttpResponse.of(cookie == null ? "" : cookie);
              })
              .and()
              .virtualHost("bar.com")
              .service("/get-cookie", (ctx, req) -> {
                  final String cookie = req.headers().get(HttpHeaderNames.COOKIE);
                  return HttpResponse.of(cookie == null ? "" : cookie);
              });
        }
    };

    @BeforeAll
    static void init() {
        port = server.httpPort();
    }

    @Test
    void setCookie() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .addressResolverGroupFactory(
                                                          group -> MockAddressResolverGroup.localhost())
                                                  .build()) {
            final WebClient client = WebClient.builder()
                                              .factory(factory)
                                              .decorator(CookieClient.newDecorator())
                                              .build();

            client.get("http://foo.com:" + port + "/set-cookie").aggregate().join();
            String cookie = client.get("http://foo.com:" + port + "/get-cookie").aggregate()
                                  .join().contentUtf8();

            final Cookies cookies = Cookie.fromCookieHeader(cookie);
            assertThat(cookies).hasSize(2)
                               // Set false to "secure" attribute to use HTTP.
                               .contains(Cookie.secureBuilder("some-cookie", "foo").secure(false).build(),
                                         Cookie.secureBuilder("some-cookie2", "bar").secure(false).build());

            cookie = client.get("http://bar.com:" + port + "/get-cookie").aggregate()
                           .join().contentUtf8();
            assertThat(cookie).isEmpty();
        }
    }
}
