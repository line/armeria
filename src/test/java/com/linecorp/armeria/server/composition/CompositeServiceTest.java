/*
 * Copyright 2016 LINE Corporation
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

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.google.common.net.MediaType;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

public class CompositeServiceTest extends AbstractServerTest {

    private static final TestService serviceA = new TestService("A");
    private static final TestService serviceB = new TestService("B");
    private static final TestService serviceC = new TestService("C");
    private static final TestService otherService = new TestService("X");

    private static final TestCompositeService composite = new TestCompositeService();

    @Override
    protected void configureServer(ServerBuilder sb) {
        sb.serviceUnder("/qux/", composite);

        // Should not hit the following services
        sb.serviceUnder("/foo/", otherService);
        sb.serviceUnder("/bar/", otherService);
        sb.service(PathMapping.ofGlob("/*"), otherService);
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

    private static final class TestCompositeService extends AbstractCompositeService<HttpRequest, HttpResponse> {
        TestCompositeService() {
            super(CompositeServiceEntry.ofPrefix("/foo/", serviceA),
                  CompositeServiceEntry.ofPrefix("/bar/", serviceB),
                  CompositeServiceEntry.ofGlob("/*", serviceC)); // Matches /x but doesn't match /x/y
        }
    }

    private static final class TestService extends AbstractHttpService {

        private final String name;

        TestService(String name) {
            this.name = name;
        }

        @Override
        protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                        "%s:%s:%s", name, ctx.path(), ctx.mappedPath());
        }
    }
}
