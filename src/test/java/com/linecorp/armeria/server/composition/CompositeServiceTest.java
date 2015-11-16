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

package com.linecorp.armeria.server.composition;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.http.HttpService;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class CompositeServiceTest extends AbstractServerTest {

    private static final TestService serviceA = new TestService("A");
    private static final TestService serviceB = new TestService("B");
    private static final TestService serviceC = new TestService("C");
    private static final TestService otherService = new TestService("X");

    private static final TestCompositeService composite = new TestCompositeService();

    @Override
    protected void configureServer(ServerBuilder sb) {
        VirtualHostBuilder defaultVirtualHost = new VirtualHostBuilder();

        defaultVirtualHost.serviceUnder("/qux/", composite);

        // Should not hit the following services
        defaultVirtualHost.serviceUnder("/foo/", otherService);
        defaultVirtualHost.serviceUnder("/bar/", otherService);
        defaultVirtualHost.service(PathMapping.ofGlob("/*"), otherService);

        sb.defaultVirtualHost(defaultVirtualHost.build());
    }

    @Test
    public void testMapping() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/qux/foo/X")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("A:/qux/foo/X:/X"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/qux/bar/Y")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("B:/qux/bar/Y:/Y"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/qux/Z")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("C:/qux/Z:/Z"));
            }

        }
    }

    @Test
    public void testNonExistentMapping() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/qux/Z/T")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
        }
    }

    @Test
    public void testServiceGetters() throws Exception {
        assertThat(composite.serviceAt(0), is(sameInstance(serviceA)));
        assertThat(composite.serviceAt(1), is(sameInstance(serviceB)));
        assertThat(composite.serviceAt(2), is(sameInstance(serviceC)));

        try {
            composite.serviceAt(-1);
            fail();
        } catch (IndexOutOfBoundsException e) {
            // Expected
        }
        try {
            composite.serviceAt(3);
            fail();
        } catch (IndexOutOfBoundsException e) {
            // Expected
        }
    }

    private static final class TestCompositeService extends AbstractCompositeService {
        TestCompositeService() {
            super(CompositeServiceEntry.ofPrefix("/foo/", serviceA),
                  CompositeServiceEntry.ofPrefix("/bar/", serviceB),
                  CompositeServiceEntry.ofGlob("/*", serviceC)); // Matches /x but doesn't match /x/y
        }
    }

    private static final class TestService extends HttpService {
        TestService(String name) {
            super((ctx, blockingTaskExecutor, promise) -> promise.setSuccess(new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(name + ':' + ctx.path() + ':' + ctx.mappedPath(),
                                          StandardCharsets.UTF_8))));
        }
    }
}
