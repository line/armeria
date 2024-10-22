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

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import com.google.common.io.ByteStreams;
import io.netty.util.NetUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RawPathTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder("/", (ctx, req) -> {
                final String rawPath = ctx.routingContext().requestTarget().rawPath();
                assertThat(rawPath).isNotNull();
                return HttpResponse.of("rawPath:" + rawPath);
            });
        }
    };

    private static final Set<String> TEST_URLS = new HashSet<>();

    static {
        TEST_URLS.add("/");
        TEST_URLS.add("//");
        TEST_URLS.add("/service//foo");
        TEST_URLS.add("/service/foo..bar");
        TEST_URLS.add("/service..hello/foobar");
        TEST_URLS.add("/service//test//////a/?flag=hello");
        TEST_URLS.add("/service/foo:bar");
        TEST_URLS.add("/service/foo::::::bar");
        TEST_URLS.add("/cache/v1.0/rnd_team/get/krisjey:56578015655:1223");
        TEST_URLS.add("/signout/56578015655?crumb=s-1475829101-cec4230588-%E2%98%83");
        TEST_URLS.add(
                "/search/num=20&newwindow=1&espv=2&q=url+path+colon&oq=url+path+colon&gs_l=serp.3" +
                        "..0i30k1.80626.89265.0.89464.18.16.1.1.1.0.154.1387.0j12.12.0....0...1c.1j4.64.s" +
                        "erp..4.14.1387...0j35i39k1j0i131k1j0i19k1j0i30i19k1j0i8i30i19k1j0i5i30i19k1j0i8i10" +
                        "i30i19k1.Z6SsEq-rZDw");
        TEST_URLS.add("/service/foo*bar4");
        TEST_URLS.add("/gwturl#user:45/comments");
        TEST_URLS.add("/service:name/hello");
        TEST_URLS.add("/service/foo|bar5");
        TEST_URLS.add("/service/foo\\bar6");
        TEST_URLS.add("/[]");
        TEST_URLS.add("/a+b");
        TEST_URLS.add("/%3A%2F%3F%23%5B%5D%40%21%24%26%27%28%29*%2B%2C%3B%3D");
    }

    @Test
    void rawPathOfUrl() throws Exception {
        for (String url : TEST_URLS) {
            rawPathAssertion(url);
        }
    }

    private static void rawPathAssertion(String path) throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST, server.httpPort())) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(
                    ("GET " + path + " HTTP/1.0\r\n" +
                            "Host:" + server.httpUri().getAuthority() + "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n").getBytes(StandardCharsets.US_ASCII));
            final String ret = new String(ByteStreams.toByteArray(s.getInputStream()),
                    StandardCharsets.US_ASCII);
            assertThat(ret).contains("rawPath:" + path);
        }
    }
}
