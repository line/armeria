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
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

public class HttpServiceTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    "/hello/{name}",
                    new AbstractHttpService() {
                        @Override
                        protected void doGet(
                                ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                            final String name = ctx.pathParam("name");
                            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
                        }
                    }.decorate(LoggingService.newDecorator()));

            sb.service(
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
                    }.decorate(LoggingService.newDecorator()));

            sb.service(
                    "/204",
                    new AbstractHttpService() {
                        @Override
                        protected void doGet(
                                ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                            res.respond(HttpStatus.NO_CONTENT);
                        }
                    }.decorate(LoggingService.newDecorator()));
        }
    };

    @Test
    public void testHello() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/hello/foo")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Hello, foo!"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/hello/foo/bar")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }

            try (CloseableHttpResponse res = hc.execute(new HttpDelete(rule.httpUri("/hello/bar")))) {
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
            HttpUriRequest req = new HttpGet(rule.httpUri("/200"));
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
            try (CloseableHttpResponse res = hc.execute(new HttpHead(rule.httpUri("/200")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getEntity(), is(nullValue()));
            }

            // Ensure the 204 response does not have content.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/204")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 204 No Content"));
                assertThat(res.getEntity(), is(nullValue()));
            }
        }
    }
}
