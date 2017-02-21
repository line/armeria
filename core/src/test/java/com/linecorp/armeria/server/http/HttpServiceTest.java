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

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.TestConverters.NaiveIntConverter;
import com.linecorp.armeria.server.http.TestConverters.NaiveNumberConverter;
import com.linecorp.armeria.server.http.TestConverters.NaiveStringConverter;
import com.linecorp.armeria.server.http.TestConverters.TypedNumberConverter;
import com.linecorp.armeria.server.http.TestConverters.TypedStringConverter;
import com.linecorp.armeria.server.http.dynamic.Converter;
import com.linecorp.armeria.server.http.dynamic.DynamicHttpService;
import com.linecorp.armeria.server.http.dynamic.DynamicHttpServiceBuilder;
import com.linecorp.armeria.server.http.dynamic.Get;
import com.linecorp.armeria.server.http.dynamic.Path;
import com.linecorp.armeria.server.http.dynamic.PathParam;
import com.linecorp.armeria.server.http.dynamic.Post;
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

            // Dynamic Service with DynamicHttpServiceBuilder and direct mappings
            sb.service(PathMapping.ofPrefix("/dynamic1"), new DynamicHttpServiceBuilder()
                    // Default ResponseConverter for Integer
                    .addConverter(Integer.class, new NaiveIntConverter())
                    // Default ResponseConverter for Number
                    .addConverter(Number.class, new NaiveNumberConverter())
                    // Default ResponseConverter for String
                    .addConverter(String.class, new NaiveStringConverter())
                    // Case 1: returns Integer type and handled by default Integer -> HttpResponse converter.
                    .addMapping(HttpMethod.GET, "/int/{var}",
                                (ctx, req, args) -> Integer.parseInt(args.get("var"))
                    )
                    .addMapping(HttpMethod.GET, "/int-async/{var}",
                                (ctx, req, args) ->
                                        CompletableFuture.completedFuture(Integer.parseInt(args.get("var")))
                                                         .thenApply(n -> n + 1)
                    )
                    // Case 2: returns Long type and handled by default Number -> HttpResponse converter.
                    .addMapping(HttpMethod.POST, "/long/{var}",
                                (ctx, req, args) -> Long.parseLong(args.get("var"))
                    )
                    // Case 3: returns String type and handled by custom String -> HttpResponse converter.
                    .addMapping(EnumSet.of(HttpMethod.GET), "/string/{var}",
                                (ctx, req, args) -> args.get("var"), new TypedStringConverter()
                    )
                    .build().decorate(LoggingService::new));
            // Dynamic Service with DynamicHttpServiceBuilder and direct mappings
            sb.service(PathMapping.ofPrefix("/dynamic2"), new DynamicHttpServiceBuilder()
                    // Default ResponseConverter for Integer
                    .addConverter(Integer.class, new NaiveIntConverter())
                    // Case 4, 5, 6
                    .addMappings(new ResponseStrategy())
                    .build().decorate(LoggingService::new));
            // Dynamic Service with inheritance
            // Case 7, 8, 9
            sb.service(PathMapping.ofPrefix("/dynamic3"), new DynamicService());
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @Converter(target = Number.class, value = TypedNumberConverter.class)
    @Converter(target = String.class, value = TypedStringConverter.class)
    public static class ResponseStrategy {
        // Case 4: returns Integer type and handled by builder-default Integer -> HttpResponse converter.
        @Get
        @Path("/int/:var")
        public int returnInt(@PathParam("var") int var) {
            return var;
        }

        // Case 5: returns Long type and handled by class-default Number -> HttpResponse converter.
        @Post
        @Path("/long/{var}")
        public CompletionStage<Long> returnLong(@PathParam("var") long var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Case 6: returns String type and handled by custom String -> HttpResponse converter.
        @Get
        @Path("/string/:var")
        @Converter(NaiveStringConverter.class)
        public CompletionStage<String> returnString(@PathParam("var") String var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Asynchronously returns Integer type and handled by builder-default Integer -> HttpResponse converter.
        @Get
        @Path("/int-async/:var")
        public CompletableFuture<Integer> returnIntAsync(@PathParam("var") int var) {
            return CompletableFuture.completedFuture(var).thenApply(n -> n + 1);
        }

        // Throws an exception synchronously
        @Get
        @Path("/exception/:var")
        public int exception(@PathParam("var") int var) {
            throw new IllegalArgumentException("bad var!");
        }

        // Throws an exception asynchronously
        @Get
        @Path("/exception-async/:var")
        public CompletableFuture<Integer> exceptionAsync(@PathParam("var") int var) {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("bad var!"));
            return future;
        }
    }

    @Converter(target = Number.class, value = TypedNumberConverter.class)
    @Converter(target = String.class, value = TypedStringConverter.class)
    public static class DynamicService extends DynamicHttpService {
        // Case 7: returns Integer type and handled by class-default Number -> HttpResponse converter.
        @Get
        @Path("/int/{var}")
        public CompletionStage<Integer> returnInt(@PathParam("var") int var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Case 8: returns Long type and handled by class-default Number -> HttpResponse converter.
        @Post
        @Path("/long/:var")
        public Long returnLong(@PathParam("var") long var) {
            return var;
        }

        // Case 9: returns String type and handled by custom String -> HttpResponse converter.
        @Get
        @Path("/string/{var}")
        @Converter(NaiveStringConverter.class)
        public String returnString(@PathParam("var") String var) {
            return var;
        }
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                         .filter(p -> p.protocol() == HttpSessionProtocols.HTTP).findAny().get().localAddress()
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
    public void testDynamicHttpServices() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Run case 1.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic1/int/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Integer: 42"));
            }
            // Run asynchronous case 1.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic1/int-async/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Integer: 43"));
            }
            // Run case 2.
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic1/long/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number: 42"));
            }
            // Run case 3.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic1/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[blah]"));
            }
            // Run case 1 but illegal parameter.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/int/fourty-two")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }
            // Run case 2 but without parameter (non-existing url).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic1/long/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
            // Run case 3 but with not-mapped HTTP method (Post).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic1/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
            // Run case 4.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/int/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Integer: 42"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/int-async/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Integer: 43"));
            }
            // Run case 5.
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic2/long/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            // Run case 6.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String: blah"));
            }
            // Run case 4 but illegal parameter.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/int/fourty-two")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }
            // Run case 5 but without parameter (non-existing url).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic2/long/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
            // Run case 6 but with not-mapped HTTP method (Post).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic2/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
            // Exceptions in business logic
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(newUri("/dynamic2/exception/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(newUri("/dynamic2/exception-async/1")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }

            // Run case 7.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic3/int/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            // Run case 8.
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic3/long/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            // Run case 9.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic3/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String: blah"));
            }
            // Run case 7 but illegal parameter.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic3/int/fourty-two")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }
            // Run case 8 but without parameter (non-existing url).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic3/long/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
            // Run case 9 but with not-mapped HTTP method (Post).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(newUri("/dynamic3/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
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
