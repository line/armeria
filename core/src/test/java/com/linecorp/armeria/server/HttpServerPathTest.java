/*
 * Copyright 2016 LINE Corporation
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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.NetUtil;

public class HttpServerPathTest {

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, SessionProtocol.HTTP);
            sb.service("/service/foo", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.serviceUnder("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
        }
    };

    private static final Map<String, HttpStatus> TEST_URLS = new HashMap<>();

    static {
        // 200 test
        TEST_URLS.put("/", HttpStatus.OK);
        TEST_URLS.put("//", HttpStatus.OK);
        TEST_URLS.put("/service//foo", HttpStatus.OK);
        TEST_URLS.put("/service/foo..bar", HttpStatus.OK);
        TEST_URLS.put("/service..hello/foobar", HttpStatus.OK);
        TEST_URLS.put("/service//test//////a/", HttpStatus.OK);
        TEST_URLS.put("/service//test//////a/?flag=hello", HttpStatus.OK);
        TEST_URLS.put("/service/foo:bar", HttpStatus.OK);
        TEST_URLS.put("/service/foo::::::bar", HttpStatus.OK);
        TEST_URLS.put("/cache/v1.0/rnd_team/get/krisjey:56578015655:1223", HttpStatus.OK);

        TEST_URLS.put("/signout/56578015655?crumb=s-1475829101-cec4230588-%E2%98%83", HttpStatus.OK);
        TEST_URLS.put(
                "/search/num=20&newwindow=1&espv=2&q=url+path+colon&oq=url+path+colon&gs_l=serp.3" +
                "..0i30k1.80626.89265.0.89464.18.16.1.1.1.0.154.1387.0j12.12.0....0...1c.1j4.64.s" +
                "erp..4.14.1387...0j35i39k1j0i131k1j0i19k1j0i30i19k1j0i8i30i19k1j0i5i30i19k1j0i8i10" +
                "i30i19k1.Z6SsEq-rZDw",
                HttpStatus.OK);
        // Should allow the asterisk character in the path
        TEST_URLS.put("/service/foo*bar4", HttpStatus.OK);

        // 400 test
        TEST_URLS.put("..", HttpStatus.BAD_REQUEST);
        TEST_URLS.put(".\\", HttpStatus.BAD_REQUEST);
        TEST_URLS.put("something", HttpStatus.BAD_REQUEST);
        TEST_URLS.put("/service/foo|bar5", HttpStatus.BAD_REQUEST);
        TEST_URLS.put("/service/foo\\bar6", HttpStatus.BAD_REQUEST);
        TEST_URLS.put("/\\\\", HttpStatus.BAD_REQUEST);
        TEST_URLS.put("/service/foo>bar", HttpStatus.BAD_REQUEST);
        TEST_URLS.put("/service/foo<bar", HttpStatus.BAD_REQUEST);

        // 404 test
        TEST_URLS.put("/..service/foobar1", HttpStatus.NOT_FOUND);
        TEST_URLS.put("/service../foobar2", HttpStatus.NOT_FOUND);
        TEST_URLS.put("/service/foobar3..", HttpStatus.NOT_FOUND);
        TEST_URLS.put("/gwturl#user:45/comments", HttpStatus.NOT_FOUND);
        TEST_URLS.put("/service:name/hello", HttpStatus.NOT_FOUND);
        TEST_URLS.put("/service::::name/hello", HttpStatus.NOT_FOUND);
    }

    @Test(timeout = 10000)
    public void testPathOfUrl() throws Exception {
        for (Entry<String, HttpStatus> url : TEST_URLS.entrySet()) {
            urlPathAssertion(url.getValue(), url.getKey());
        }
    }

    private static void urlPathAssertion(HttpStatus expected, String path) throws Exception {
        final String requestString = "GET " + path + " HTTP/1.0\r\n\r\n";

        try (Socket s = new Socket(NetUtil.LOCALHOST, server.httpPort())) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(requestString.getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(ByteStreams.toByteArray(s.getInputStream()), StandardCharsets.US_ASCII))
                    .as(path)
                    .startsWith("HTTP/1.1 " + expected);
        }
    }
}
