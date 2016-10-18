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
package com.linecorp.armeria.server.http;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.net.MediaType;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;

public class HttpServiceTest {

    private static final Server server;

    private static int httpPort;

    static {
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.service(
                    PathMapping.ofGlob("/hello/*").stripPrefix(1),
                    new AbstractHttpService() {
                        @Override
                        protected void doGet(
                                ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                            final String name = ctx.mappedPath().substring(1);
                            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
                        }
                    }.decorate(LoggingService::new))
              .serviceAt(
                      "/200",
                      new AbstractHttpService() {
                          @Override
                          protected void doHead(
                                  ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                              res.respond(HttpStatus.OK);
                          }

                          @Override
                          protected void doGet(
                                  ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                              res.respond(HttpStatus.OK);
                          }
                      }.decorate(LoggingService::new))
              .serviceAt(
                      "/204",
                      new AbstractHttpService() {
                          @Override
                          protected void doGet(
                                  ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                              res.respond(HttpStatus.NO_CONTENT);
                          }
                      }.decorate(LoggingService::new));
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                         .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress()
                         .getPort();
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
                assertThat(EntityUtils.toString(res.getEntity()), is("405 Method Not Allowed"));
            }
        }
    }

    @Test
    public void testContentLength() throws Exception {
        // Test if the server responds with the 'content-length' header
        // even if it is the last response of the connection.
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpUriRequest req = new HttpGet(newUri("/200"));
            req.setHeader("Connection", "Close");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.containsHeader("Content-Length"), is(true));
                assertThat(res.getHeaders("Content-Length").length, is(1));
                assertThat(res.getHeaders("Content-Length")[0].getValue(), is("6"));
                assertThat(EntityUtils.toString(res.getEntity()), is("200 OK"));
            }
        }

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Ensure the HEAD response does not have content.
            try (CloseableHttpResponse res = hc.execute(new HttpHead(newUri("/200")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getEntity(), is(nullValue()));
            }

            // Ensure the 204 response does not have content.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/204")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 204 No Content"));
                assertThat(res.getEntity(), is(nullValue()));
            }
        }
    }

    private static String newUri(String path) {
        return "http://127.0.0.1:" + httpPort + path;
    }
}
