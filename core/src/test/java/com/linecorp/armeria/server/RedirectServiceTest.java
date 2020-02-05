/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class RedirectServiceTest {

    static final HttpService SERVICE_BRANCH_1 = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "SERVICE_BRANCH_1");
        }
    };
    static final HttpService SERVICE_BRANCH_2 = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "SERVICE_BRANCH_2");
        }
    };
    static final HttpService SERVICE_NAMED_PARAM = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final String value1 = ctx.pathParam("var1");
            final String value2 = ctx.pathParam("var2");
            final String value3 = ctx.pathParam("var3");
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                   "SERVICE_NAMED_PARAM %s %s %s", value1, value2, value3);
        }
    };

    @Rule
    public final ServerRule serverRule1 = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int serverRule1Port = ss.getLocalPort();

                sb.http(serverRule1Port);
                sb.service("/new0/branch1", SERVICE_BRANCH_1);
                sb.service("/new0/branch2", SERVICE_BRANCH_2);

                sb.service("/new1/{var1}/new1/{var2}/new1/{var3}", SERVICE_NAMED_PARAM);

                sb.service("/test1a", new RedirectService("/new0/branch1"));
                sb.service("/test1b", new RedirectService(ctx -> "/new0/branch1"));
                sb.service("/test1c", new RedirectService(HttpStatus.MULTIPLE_CHOICES, "/new0/branch1"));
                sb.service("/test1d", new RedirectService(HttpStatus.MOVED_PERMANENTLY, "/new0/branch1"));
                sb.service("/test1e", new RedirectService(HttpStatus.FOUND, "/new0/branch1"));
                sb.service("/test1f", new RedirectService(HttpStatus.SEE_OTHER, "/new0/branch1"));
                sb.service("/test1g", new RedirectService(HttpStatus.NOT_MODIFIED, "/new0/branch1"));
                sb.service("/test1h", new RedirectService(HttpStatus.USE_PROXY, "/new0/branch1"));
                sb.service("/test1i", new RedirectService(HttpStatus.TEMPORARY_REDIRECT, "/new0/branch1"));
                sb.service("/test1j",
                           new RedirectService(HttpStatus.TEMPORARY_REDIRECT, ctx -> "/new0/branch1"));

                sb.service("/test1k/{var1}", new RedirectService("/new0/{var1}"));

                sb.service("/test2a/{var1}/{var2}/{var3}", new RedirectService(
                        "/new1/{var1}/new1/{var2}/new1/{var3}"));
                sb.service("/test2b/:var1/:var2/:var3", new RedirectService(
                        "/new1/:var1/new1/:var2/new1/:var3"));
                sb.service("regex:/test2c/(?<var1>.*)/(?<var2>.*)/(?<var3>.*)", new RedirectService(
                        "/new1/:var1/new1/:var2/new1/:var3"));
                sb.service("glob:/test2d/*/*/*", new RedirectService(
                        "/new1/:0/new1/:1/new1/:2"));

                sb.service("/test3a", new RedirectService("http://localhost:" + serverRule1Port +
                                                          "/new0/branch1"));
                sb.service("/test3b", new RedirectService("http://127.0.0.1:" + serverRule1Port +
                                                          "/new0/branch1"));
                sb.service("/test3c/{var1}", new RedirectService("http://localhost:" + serverRule1Port +
                                                                 "/new0/{var1}"));
                sb.service("/test3d/{var1}", new RedirectService("http://127.0.0.1:" + serverRule1Port +
                                                                 "/new0/{var1}"));

                // For testing preserveQueryString option.
                sb.service("/query_string/preserved", new RedirectService("/new"));
                sb.service("/query_string/unpreserved", new RedirectService("/new", false));
                sb.service("/query_string/unpreserved_2", new RedirectService("/new?redirected=1"));

                sb.build();
            }
        }
    };

    @Rule
    public final ServerRule serverRule2a = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/test1a/{var4}/{var5}/{var1}", new RedirectService(
                    "/new1/{var4}/new1/{var5}/new1/{var6}"));
        }
    };
    @Rule
    public final ServerRule serverRule2b = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/test1a/{var1}/{var2}/{var3}", new RedirectService(
                    "/new1/{var4}/new1/{var5}/new1/{var6}"));
        }
    };
    @Rule
    public final ServerRule serverRule2c = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/test1a/{var1}/{var2}/{var3}",
                       new RedirectService(
                               ctx -> "/new1/" + ctx.pathParam("var4") + "/new1/" + ctx.pathParam("var5") +
                                      "/new1/" + ctx.pathParam("var6")));
        }
    };
    @Rule
    public final ServerRule serverRule2d = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("regex:/test1a/(?<var4>.*)/(?<var5>.*)/(?<var1>.*)",
                       new RedirectService(
                               "/new1/{var4}/new1/{var5}/new1/{var6}"));
        }
    };
    @Rule
    public final ServerRule serverRule2e = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("glob:/test1a/*/*/*",
                       new RedirectService(
                               "/new1/{var4}/new1/{var5}/new1/{var6}"));
        }
    };

    @Test
    public void testRedirectOK() throws Exception {
        serverRule1.start();
        final String[] testPaths = {
                "/test1a",
                "/test1b",
                "/test1c",
                "/test1d",
                "/test1e",
                "/test1f",
                "/test1g",
                "/test1h",
                "/test1i",
                "/test1j",
                "/test1k/branch1",
                "/test1k/branch2",

                "/test2a/qwe/asd/zxc",
                "/test2b/rty/fgh/vbn",
                "/test2c/qwe/asd/zxc",
                "/test2d/rty/fgh/vbn",

                "/test3a",
                "/test3b",
                "/test3c/branch1",
                "/test3d/branch2",

                "/query_string/preserved?foo=bar",
                "/query_string/unpreserved?foo=bar",
                "/query_string/unpreserved_2?foo=bar"
        };
        final HttpStatus[] redirectStatuses = {
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.MULTIPLE_CHOICES,
                HttpStatus.MOVED_PERMANENTLY,
                HttpStatus.FOUND,
                HttpStatus.SEE_OTHER,
                HttpStatus.NOT_MODIFIED,
                HttpStatus.USE_PROXY,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,

                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,

                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,

                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                HttpStatus.TEMPORARY_REDIRECT,
                };
        final String[] expectedLocations = {
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch1",
                "/new0/branch2",

                "/new1/qwe/new1/asd/new1/zxc",
                "/new1/rty/new1/fgh/new1/vbn",
                "/new1/qwe/new1/asd/new1/zxc",
                "/new1/rty/new1/fgh/new1/vbn",

                "http://localhost:" + serverRule1.httpPort() + "/new0/branch1",
                "http://127.0.0.1:" + serverRule1.httpPort() + "/new0/branch1",
                "http://localhost:" + serverRule1.httpPort() + "/new0/branch1",
                "http://127.0.0.1:" + serverRule1.httpPort() + "/new0/branch2",

                "/new?foo=bar",
                "/new",
                "/new?redirected=1"
        };
        final String[] expectedResponse = {
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_2",

                "SERVICE_NAMED_PARAM qwe asd zxc",
                "SERVICE_NAMED_PARAM rty fgh vbn",
                "SERVICE_NAMED_PARAM qwe asd zxc",
                "SERVICE_NAMED_PARAM rty fgh vbn",

                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_1",
                "SERVICE_BRANCH_2",

                null,
                null,
                null
        };

        final WebClient client = WebClient.of(serverRule1.httpUri());
        for (int i = 0; i < testPaths.length; ++i) {
            AggregatedHttpResponse res = client.get(testPaths[i]).aggregate().get();
            assertThat(res.status()).isEqualTo(redirectStatuses[i]);

            final String newLocation = res.headers().get(HttpHeaderNames.LOCATION);
            assertThat(newLocation).isEqualTo(expectedLocations[i]);

            if (expectedResponse[i] == null) {
                continue;
            }

            if (newLocation.startsWith("http")) {
                final WebClient client2 = WebClient.of(newLocation);
                res = client2.get("").aggregate().get();
            } else {
                res = client.get(newLocation).aggregate().get();
            }

            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentUtf8()).isEqualTo(expectedResponse[i]);
        }
        serverRule1.stop();
    }

    @Test
    public void testMisconfiguredIllegalStatus1() throws Exception {
        assertThatThrownBy(() -> new RedirectService(HttpStatus.OK, "/new0/branch1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("redirectStatus: 200 OK (expected: 300 .. 307)");
    }

    @Test
    public void testMisconfiguredIllegalStatus2() throws Exception {
        assertThatThrownBy(() -> new RedirectService(HttpStatus.OK, ctx -> "/new0/branch1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("redirectStatus: 200 OK (expected: 300 .. 307)");
    }

    @Test
    public void testMisconfiguredIllegalStatus3() throws Exception {
        assertThatThrownBy(() -> new RedirectService(HttpStatus.BAD_REQUEST, "/new0/branch1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("redirectStatus: 400 Bad Request (expected: 300 .. 307)");
    }

    @Test
    public void testMisconfiguredIllegalStatus4() throws Exception {
        assertThatThrownBy(() -> new RedirectService(HttpStatus.BAD_REQUEST, ctx -> "/new0/branch1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("redirectStatus: 400 Bad Request (expected: 300 .. 307)");
    }

    @Test
    public void testMisconfiguredIllegalPathGlob() throws Exception {
        final String locationPattern = "glob:/new1/*/new1/*/new1/*";
        assertThatThrownBy(() -> new RedirectService(locationPattern))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("locationPattern: " + locationPattern);
    }

    @Test
    public void testMisconfiguredIllegalPathRegex() throws Exception {
        final String locationPattern = "regex:/new1/(?<var1>.*)/(?<var2>.*)";
        assertThatThrownBy(() -> new RedirectService(locationPattern))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("locationPattern: " + locationPattern);
    }

    @Test
    public void testMisconfiguredIllegalPathPrefix() throws Exception {
        final String locationPattern = "prefix:/new1";
        assertThatThrownBy(() -> new RedirectService(locationPattern))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("locationPattern: " + locationPattern);
    }

    @Test
    public void testMisconfiguredPathParams1() throws Exception {
        assertThatThrownBy(serverRule2a::start)
                .isInstanceOf(IllegalStateException.class)
                .hasCause(new IllegalArgumentException(
                        "pathParams: var6 (no matching param in [var4, var5, var1])"));
    }

    @Test
    public void testMisconfiguredPathParams2() throws Exception {
        assertThatThrownBy(serverRule2b::start)
                .isInstanceOf(IllegalStateException.class)
                .hasCause(new IllegalArgumentException(
                        "pathParams: var4 (no matching param in [var1, var2, var3])"));
    }

    @Test
    public void testMisconfiguredPathParams3() throws Exception {
        serverRule2c.start();

        final WebClient client = WebClient.of(serverRule2c.httpUri());
        AggregatedHttpResponse res = client.get("/test1a/qwe/asd/zxc").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);

        final String newLocation = res.headers().get(HttpHeaderNames.LOCATION);
        assertThat(newLocation).isEqualTo("/new1/null/new1/null/new1/null");

        res = client.get(newLocation).aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);

        serverRule2c.stop();
    }

    @Test
    public void testMisconfiguredPathParams4() throws Exception {
        assertThatThrownBy(serverRule2d::start)
                .isInstanceOf(IllegalStateException.class)
                .hasCause(new IllegalArgumentException(
                        "pathParams: var6 (no matching param in [var4, var5, var1])"));
    }

    @Test
    public void testMisconfiguredPathParams5() throws Exception {
        assertThatThrownBy(serverRule2e::start)
                .isInstanceOf(IllegalStateException.class)
                .hasCause(new IllegalArgumentException(
                        "pathParams: var4 (no matching param in [0, 1, 2])"));
    }
}
