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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
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
import com.linecorp.armeria.server.http.TestConverters.UnformattedStringConverter;
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
            sb.serviceAt(
                    "/hello/{name}",
                    new AbstractHttpService() {
                        @Override
                        protected void doGet(
                                ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                            final String name = ctx.pathParam("name");
                            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
                        }
                    }.decorate(LoggingService.newDecorator()));

            sb.serviceAt(
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

            sb.serviceAt(
                    "/204",
                    new AbstractHttpService() {
                        @Override
                        protected void doGet(
                                ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

                            res.respond(HttpStatus.NO_CONTENT);
                        }
                    }.decorate(LoggingService.newDecorator()));

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
                    .build().decorate(LoggingService.newDecorator()));
            // Dynamic Service with DynamicHttpServiceBuilder and direct mappings
            sb.service(PathMapping.ofPrefix("/dynamic2"), new DynamicHttpServiceBuilder()
                    // Default ResponseConverter for Integer
                    .addConverter(Integer.class, new NaiveIntConverter())
                    // Case 4, 5, 6
                    .addMappings(new ResponseStrategy())
                    .build().decorate(LoggingService.newDecorator()));
            // Dynamic Service with inheritance
            // Case 7, 8, 9
            sb.service(PathMapping.ofPrefix("/dynamic3"), new DynamicService());
            sb.serviceUnder("/dynamic4",
                            new DynamicHttpServiceBuilder()
                                    .addMappings(new SimpleDynamicService1())
                                    .addMappings(new SimpleDynamicService2())
                                    .addMappings(new SimpleDynamicService3())
                                    .build()
                                    .orElse(new AbstractHttpService() {}));
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

        @Get
        @Path("/path/ctx/async/:var")
        public static CompletableFuture<String> returnPathCtxAsync(@PathParam("var") int var,
                                                                   ServiceRequestContext ctx,
                                                                   Request req) {
            validateContextAndRequest(ctx, req);
            return CompletableFuture.completedFuture(ctx.path());
        }

        @Get
        @Path("/path/req/async/:var")
        public static CompletableFuture<String> returnPathReqAsync(@PathParam("var") int var,
                                                                   HttpRequest req,
                                                                   ServiceRequestContext ctx) {
            validateContextAndRequest(ctx, req);
            return CompletableFuture.completedFuture(req.path());
        }

        @Get
        @Path("/path/ctx/sync/:var")
        public static String returnPathCtxSync(@PathParam("var") int var,
                                               RequestContext ctx,
                                               Request req) {
            validateContextAndRequest(ctx, req);
            return ctx.path();
        }

        @Get
        @Path("/path/req/sync/:var")
        public static String returnPathReqSync(@PathParam("var") int var,
                                               HttpRequest req,
                                               RequestContext ctx) {
            validateContextAndRequest(ctx, req);
            return req.path();
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

        @Get
        @Path("/boolean/{var}")
        public String returnBoolean(@PathParam("var") boolean var) {
            return Boolean.toString(var);
        }
    }

    @Converter(target = Number.class, value = TypedNumberConverter.class)
    public static class SimpleDynamicService1 {
        @Get
        @Path("/int/{var}")
        public CompletionStage<Integer> returnInt(@PathParam("var") int var) {
            return CompletableFuture.supplyAsync(() -> var);
        }
    }

    @Converter(target = String.class, value = TypedStringConverter.class)
    public static class SimpleDynamicService2 {
        @Get
        @Path("/string/{var}")
        public String returnString(@PathParam("var") String var) {
            return var;
        }

        @Get
        @Path("/no-path-param")
        public String noPathParam() {
            return "no-path-param";
        }
    }

    // Aggregation Test
    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class SimpleDynamicService3 {
        @Post
        @Path("/a/string")
        public String postString(AggregatedHttpMessage message, RequestContext ctx) {
            validateContext(ctx);
            return message.content().toStringUtf8();
        }

        @Post
        @Path("/a/string-async1")
        public CompletionStage<String> postStringAsync1(AggregatedHttpMessage message, RequestContext ctx) {
            validateContext(ctx);
            return CompletableFuture.supplyAsync(() -> message.content().toStringUtf8());
        }

        @Post
        @Path("/a/string-async2")
        public HttpResponse postStringAsync2(AggregatedHttpMessage message, RequestContext ctx) {
            validateContext(ctx);
            DefaultHttpResponse response = new DefaultHttpResponse();
            response.write(HttpHeaders.of(HttpStatus.OK));
            response.write(message.content());
            response.close();
            return response;
        }

        @Post
        @Path("/a/string-aggregate-response1")
        public AggregatedHttpMessage postStringAggregateResponse1(AggregatedHttpMessage message,
                                                                  RequestContext ctx) {
            validateContext(ctx);
            return AggregatedHttpMessage.of(HttpHeaders.of(HttpStatus.OK), message.content());
        }

        @Post
        @Path("/a/string-aggregate-response2")
        public AggregatedHttpMessage postStringAggregateResponse2(HttpRequest req,
                                                                  RequestContext ctx) {
            validateContextAndRequest(ctx, req);
            AggregatedHttpMessage message = req.aggregate().join();
            return AggregatedHttpMessage.of(HttpHeaders.of(HttpStatus.OK), message.content());
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
            // Get a requested path as typed string from ServiceRequestContext or HttpRequest
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/path/ctx/async/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/dynamic2/path/ctx/async/1]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/path/req/async/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/dynamic2/path/req/async/1]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/path/ctx/sync/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/dynamic2/path/ctx/sync/1]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic2/path/req/sync/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/dynamic2/path/req/sync/1]"));
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
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic3/boolean/true")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[true]"));
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

            // Test OrElseHttpService
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic4/int/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic4/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[blah]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic4/no-path-param")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[no-path-param]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/dynamic4/undefined")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 405 Method Not Allowed"));
            }
        }
    }

    @Test
    public void testDynamicHttpService_aggregation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpPost httpPost;

            httpPost = newHttpPost("/dynamic4/a/string");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/dynamic4/a/string-async1");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/dynamic4/a/string-async2");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/dynamic4/a/string-aggregate-response1");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/dynamic4/a/string-aggregate-response2");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
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

    private static HttpPost newHttpPost(String path) throws UnsupportedEncodingException {
        HttpPost httpPost = new HttpPost(newUri(path));

        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("username", "armeria"));
        params.add(new BasicNameValuePair("password", "armeria"));
        httpPost.setEntity(new UrlEncodedFormEntity(params));

        return httpPost;
    }

    private static void validateContext(RequestContext ctx) {
        if (RequestContext.current() != ctx) {
            throw new RuntimeException("ServiceRequestContext instances are not same!");
        }
    }

    private static void validateContextAndRequest(RequestContext ctx, Object req) {
        validateContext(ctx);
        if (RequestContext.current().request() != req) {
            throw new RuntimeException("HttpRequest instances are not same!");
        }
    }
}
