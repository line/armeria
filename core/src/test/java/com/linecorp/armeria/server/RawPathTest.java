/*
 * Copyright 2024 LINE Corporation
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RawPathTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder("/", (ctx, req) -> {
                final String rawPath = ctx.routingContext().requestTarget().rawPath();
                assertThat(rawPath).isNotNull();
                assertThat(ctx.rawPath()).isEqualTo(rawPath);

                final String expectedRawPath = ctx.request().headers().get("X-Raw-Path");
                assertThat(rawPath).isEqualTo(expectedRawPath);
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "//",
            "/service//foo",
            "/service/foo..bar",
            "/service..hello/foobar",
            "/service//test//////a/",
            "/service//test//////a/?flag=hello",
            "/service/foo:bar",
            "/service/foo::::::bar",
            "/another/foo/",
            "/cache/v1.0/rnd_team/get/krisjey:56578015655:1223",
            "/signout/56578015655?crumb=s-1475829101-cec4230588-%E2%98%83",
            "/search/num=20&newwindow=1&espv=2&q=url+path+colon&oq=url+path+colon&gs_l=serp.3" +
            "..0i30k1.80626.89265.0.89464.18.16.1.1.1.0.154.1387.0j12.12.0....0...1c.1j4.64.serp" +
            "..4.14.1387...0j35i39k1j0i131k1j0i19k1j0i30i19k1j0i8i30i19k1j0i5i30i19k1j0i8i10i30i19k1" +
            ".Z6SsEq-rZDw",
            "/service/foo*bar4",
            "/gwturl#user:45/comments",
            "/service:name/hello",
            "/service::::name/hello",
            "/..service/foobar1",
            "/service../foobar2",
            "/service/foobar3..",
            "/service/foo|bar5",
            "/service/foo\\bar6",
            "/\\\\",
            "/\"?\"",
            "/service/foo>bar",
            "/service/foo<bar",
            "/[]",
            "/a+b",
            "/a%20b",
            "/%3A%2F%3F%23%5B%5D%40%21%24%26%27%28%29*%2B%2C%3B%3D"
    })
    void rawPathOfUrl(String url) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(server.httpSocketAddress());
            s.setSoTimeout(10000);
            s.getOutputStream().write(
                    ("GET " + url + " HTTP/1.0\r\n" +
                            "Host:" + server.httpUri().getAuthority() + "\r\n" +
                            "Connection: close\r\n" +
                            "X-Raw-Path: " + url + "\r\n" +
                            "\r\n").getBytes(StandardCharsets.US_ASCII));
            final BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            // only reads a first line because it only needs to check the expected status
            // and does not wait for the server to close the connection.
            assertThat(in.readLine())
                    .as(url)
                    .startsWith("HTTP/1.1 200");
        }
    }
}
