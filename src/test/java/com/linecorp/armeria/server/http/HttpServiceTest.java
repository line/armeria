/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.http;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

public class HttpServiceTest {

    private static final Server server;

    private static int httpPort;

    static {
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.port(0, SessionProtocol.HTTP);

            final VirtualHostBuilder defaultVirtualHost = new VirtualHostBuilder();

            defaultVirtualHost.service(
                    PathMapping.ofGlob("/hello/*").stripPrefix(1),
                    new HttpService((ctx, exec, promise) -> {
                        final FullHttpRequest req = ctx.originalRequest();
                        final String name = ctx.mappedPath().substring(1);
                        final FullHttpResponse res;

                        if (req.method() == HttpMethod.GET) {
                            res = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                    Unpooled.copiedBuffer("Hello, " + name + '!', CharsetUtil.UTF_8));
                        } else {
                            res = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED,
                                    Unpooled.copiedBuffer("Nice try, " + name + '!', CharsetUtil.UTF_8));
                        }

                        res.headers().set(HttpHeaderNames.CONTENT_ENCODING, "text/plain; charset=UTF-8");

                        promise.setSuccess(res);
                    }).decorate(LoggingService::new));

            sb.defaultVirtualHost(defaultVirtualHost.build());
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().sync();

        httpPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress().getPort();
    }

    @AfterClass
    public static void destroy() throws Exception {
        server.stop();
    }

    @Test
    public void testHello() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/hello/foo")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Hello, foo!"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/hello/foo/bar")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpDelete(newUri("/hello/bar")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 405 Method Not Allowed"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Nice try, bar!"));
            }
        }
    }

    private static String newUri(String path) {
        return "http://127.0.0.1:" + httpPort + path;
    }
}
