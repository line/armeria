/*
 * Copyright 2017 LINE Corporation
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.TestConverters.NaiveIntConverter;
import com.linecorp.armeria.server.TestConverters.NaiveStringConverter;
import com.linecorp.armeria.server.TestConverters.TypedNumberConverter;
import com.linecorp.armeria.server.TestConverters.TypedStringConverter;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverter;
import com.linecorp.armeria.server.annotation.Converter;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Optional;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.common.AnticipatedException;
import com.linecorp.armeria.testing.server.ServerRule;

public class AnnotatedHttpServiceTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Case 1, 2, and 3, with a converter map
            sb.annotatedService("/1", new MyAnnotatedService1(),
                                ImmutableMap.of(Integer.class, new NaiveIntConverter()),
                                LoggingService.newDecorator());

            // Case 4, 5, and 6
            sb.annotatedService("/2", new MyAnnotatedService2(),
                                LoggingService.newDecorator());

            // Bind more than one service under the same path prefix.
            sb.annotatedService("/3", new MyAnnotatedService3(),
                                LoggingService.newDecorator());

            sb.annotatedService("/3", new MyAnnotatedService4(),
                                LoggingService.newDecorator());

            sb.annotatedService("/3", new MyAnnotatedService5(),
                                LoggingService.newDecorator());

            // Bind using non-default path mappings
            sb.annotatedService("/6", new MyAnnotatedService6(),
                                LoggingService.newDecorator());

            sb.annotatedService("/7", new MyAnnotatedService7(),
                                LoggingService.newDecorator());
        }
    };

    @Converter(target = Number.class, value = TypedNumberConverter.class)
    @Converter(target = String.class, value = TypedStringConverter.class)
    public static class MyAnnotatedService1 {
        // Case 1: returns Integer type and handled by builder-default Integer -> HttpResponse converter.
        @Get
        @Path("/int/:var")
        public int returnInt(@Param("var") int var) {
            return var;
        }

        // Case 2: returns Long type and handled by class-default Number -> HttpResponse converter.
        @Post
        @Path("/long/{var}")
        public CompletionStage<Long> returnLong(@Param("var") long var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Case 3: returns String type and handled by custom String -> HttpResponse converter.
        @Get
        @Path("/string/:var")
        @Converter(NaiveStringConverter.class)
        public CompletionStage<String> returnString(@Param("var") String var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Asynchronously returns Integer type and handled by builder-default Integer -> HttpResponse converter.
        @Get
        @Path("/int-async/:var")
        public CompletableFuture<Integer> returnIntAsync(@Param("var") int var) {
            return CompletableFuture.completedFuture(var).thenApply(n -> n + 1);
        }

        @Get
        @Path("/path/ctx/async/:var")
        public static CompletableFuture<String> returnPathCtxAsync(@Param("var") int var,
                                                                   ServiceRequestContext ctx,
                                                                   Request req) {
            validateContextAndRequest(ctx, req);
            return CompletableFuture.completedFuture(ctx.path());
        }

        @Get
        @Path("/path/req/async/:var")
        public static CompletableFuture<String> returnPathReqAsync(@Param("var") int var,
                                                                   HttpRequest req,
                                                                   ServiceRequestContext ctx) {
            validateContextAndRequest(ctx, req);
            return CompletableFuture.completedFuture(req.path());
        }

        @Get
        @Path("/path/ctx/sync/:var")
        public static String returnPathCtxSync(@Param("var") int var,
                                               RequestContext ctx,
                                               Request req) {
            validateContextAndRequest(ctx, req);
            return ctx.path();
        }

        @Get
        @Path("/path/req/sync/:var")
        public static String returnPathReqSync(@Param("var") int var,
                                               HttpRequest req,
                                               RequestContext ctx) {
            validateContextAndRequest(ctx, req);
            return req.path();
        }

        // Throws an exception synchronously
        @Get
        @Path("/exception/:var")
        public int exception(@Param("var") int var) {
            throw new AnticipatedException("bad var!");
        }

        // Throws an exception asynchronously
        @Get
        @Path("/exception-async/:var")
        public CompletableFuture<Integer> exceptionAsync(@Param("var") int var) {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            future.completeExceptionally(new AnticipatedException("bad var!"));
            return future;
        }
    }

    @Converter(target = Number.class, value = TypedNumberConverter.class)
    @Converter(target = String.class, value = TypedStringConverter.class)
    public static class MyAnnotatedService2 {
        // Case 4: returns Integer type and handled by class-default Number -> HttpResponse converter.
        @Get
        @Path("/int/{var}")
        public CompletionStage<Integer> returnInt(@Param("var") int var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Case 5: returns Long type and handled by class-default Number -> HttpResponse converter.
        @Post
        @Path("/long/:var")
        public Long returnLong(@Param("var") long var) {
            return var;
        }

        // Case 6: returns String type and handled by custom String -> HttpResponse converter.
        @Get
        @Path("/string/{var}")
        @Converter(NaiveStringConverter.class)
        public String returnString(@Param("var") String var) {
            return var;
        }

        @Get
        @Path("/boolean/{var}")
        public String returnBoolean(@Param("var") boolean var) {
            return Boolean.toString(var);
        }
    }

    @Converter(target = Number.class, value = TypedNumberConverter.class)
    public static class MyAnnotatedService3 {
        @Get
        @Path("/int/{var}")
        public CompletionStage<Integer> returnInt(@Param("var") int var) {
            return CompletableFuture.supplyAsync(() -> var);
        }
    }

    @Converter(target = String.class, value = TypedStringConverter.class)
    public static class MyAnnotatedService4 {
        @Get
        @Path("/string/{var}")
        public String returnString(@Param("var") String var) {
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
    public static class MyAnnotatedService5 {
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

    /**
     * An annotated service that's used for testing non-default path mappings.
     */
    @Converter(target = String.class, value = TypedStringConverter.class)
    public static class MyAnnotatedService6 {

        @Get
        @Path("exact:/exact")
        public String exact(ServiceRequestContext ctx) {
            return "exact:" + ctx.path();
        }

        @Get
        @Path("prefix:/prefix")
        public String prefix(ServiceRequestContext ctx) {
            return "prefix:" + ctx.path() + ':' + ctx.mappedPath();
        }

        @Get
        @Path("glob:/glob1/*") // The pattern that starts with '/'
        public String glob1(ServiceRequestContext ctx) {
            return "glob1:" + ctx.path();
        }

        @Get
        @Path("glob:glob2") // The pattern that does not start with '/'
        public String glob2(ServiceRequestContext ctx) {
            // When this method is bound with a prefix 'foo', the path mapping of this method will be:
            // - /foo/**/glob2
            // Even if the resulting path mapping contains '**', ctx.pathParams().size() must be 0
            // because a user did not specify it.
            return "glob2:" + ctx.path() + ':' + ctx.pathParams().size();
        }

        @Get
        @Path("regex:^/regex/(?<path>.*)$")
        public String regex(ServiceRequestContext ctx, @Param("path") String path) {
            return "regex:" + ctx.path() + ':' + path;
        }
    }

    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class MyAnnotatedService7 {

        @Get("/param/get")
        public String paramGet(RequestContext ctx,
                               @Param("username") String username,
                               @Param("password") String password) {
            validateContext(ctx);
            return username + "/" + password;
        }

        @Post("/param/post")
        public String paramPost(RequestContext ctx,
                                @Param("username") String username,
                                @Param("password") String password) {
            validateContext(ctx);
            return username + "/" + password;
        }

        @Get
        @Path("/map/get")
        public String mapGet(RequestContext ctx, HttpParameters parameters) {
            validateContext(ctx);
            return parameters.get("username") + "/" + parameters.get("password");
        }

        @Post
        @Path("/map/post")
        public String mapPost(RequestContext ctx, HttpParameters parameters) {
            validateContext(ctx);
            return parameters.get("username") + "/" + parameters.get("password");
        }

        @Get
        @Path("/param/default1")
        public String paramDefault1(RequestContext ctx,
                                    @Param("username") @Optional("hello") String username,
                                    @Param("password") @Optional("world") String password,
                                    @Param("extra") @Optional String extra) {
            // "extra" might be null because there is no default value specified.
            validateContext(ctx);
            return username + "/" + password + "/" + (extra != null ? extra : "(null)");
        }

        @Get
        @Path("/param/default2")
        public String paramDefault2(RequestContext ctx,
                                    @Param("username") @Optional("hello") String username,
                                    @Param("password") String password) {
            validateContext(ctx);
            return username + "/" + password;
        }

        @Get
        @Path("/param/precedence/{username}")
        public String paramPrecedence(RequestContext ctx,
                                      @Param("username") String username,
                                      @Param("password") String password) {
            validateContext(ctx);
            return username + "/" + password;
        }
    }

    @Test
    public void testAnnotatedHttpService() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Run case 1.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/1/int/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Integer: 42"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/1/int-async/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Integer: 43"));
            }
            // Run case 2.
            try (CloseableHttpResponse res = hc.execute(new HttpPost(rule.httpUri("/1/long/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            // Run case 3.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/1/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String: blah"));
            }
            // Run case 1 but illegal parameter.
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/1/int/fourty-two")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 400 Bad Request"));
            }
            // Run case 2 but without parameter (non-existing url).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(rule.httpUri("/1/long/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
            // Run case 3 but with not-mapped HTTP method (Post).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(rule.httpUri("/1/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 405 Method Not Allowed"));
            }
            // Get a requested path as typed string from ServiceRequestContext or HttpRequest
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/1/path/ctx/async/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/1/path/ctx/async/1]"));
            }
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/1/path/req/async/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/1/path/req/async/1]"));
            }
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/1/path/ctx/sync/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/1/path/ctx/sync/1]"));
            }
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/1/path/req/sync/1")))) {
                assertThat(EntityUtils.toString(res.getEntity()), is("String[/1/path/req/sync/1]"));
            }
            // Exceptions in business logic
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/1/exception/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/1/exception-async/1")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }

            // Run case 4.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/2/int/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            // Run case 5.
            try (CloseableHttpResponse res = hc.execute(new HttpPost(rule.httpUri("/2/long/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            // Run case 6.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/2/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String: blah"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/2/boolean/true")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[true]"));
            }
            // Run case 4 but illegal parameter.
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/2/int/fourty-two")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 400 Bad Request"));
            }
            // Run case 5 but without parameter (non-existing url).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(rule.httpUri("/2/long/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
            // Run case 6 but with not-mapped HTTP method (Post).
            try (CloseableHttpResponse res = hc.execute(new HttpPost(rule.httpUri("/2/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 405 Method Not Allowed"));
            }

            // Test the case where multiple annotated services are bound under the same path prefix.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/3/int/42")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Number[42]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/3/string/blah")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[blah]"));
            }
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(rule.httpUri("/3/no-path-param")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[no-path-param]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/3/undefined")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
        }
    }

    @Test
    public void testNonDefaultPathMappings() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Exact pattern
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/6/exact")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[exact:/6/exact]"));
            }

            // Prefix pattern
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/6/prefix/foo")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[prefix:/6/prefix/foo:/foo]"));
            }

            // Glob pattern
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/6/glob1/bar")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[glob1:/6/glob1/bar]"));
            }
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/6/baz/glob2")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[glob2:/6/baz/glob2:0]"));
            }

            // Regex pattern
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri("/6/regex/foo/bar")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("String[regex:/6/regex/foo/bar:foo/bar]"));
            }
        }
    }

    @Test
    public void testAggregation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpPost httpPost;

            httpPost = newHttpPost("/3/a/string");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/3/a/string-async1");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/3/a/string-async2");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/3/a/string-aggregate-response1");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
            httpPost = newHttpPost("/3/a/string-aggregate-response2");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()),
                           is(EntityUtils.toString(httpPost.getEntity())));
            }
        }
    }

    @Test
    public void testParam() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpPost httpPost;

            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(rule.httpUri("/7/param/get?username=line1&password=armeria1")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("line1/armeria1"));
            }

            httpPost = newHttpPost("/7/param/post", "line2", "armeria2", StandardCharsets.UTF_8);
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("line2/armeria2"));
            }

            httpPost = newHttpPost("/7/param/post", "안녕하세요", "こんにちは", StandardCharsets.UTF_8);
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity(), StandardCharsets.UTF_8), is("안녕하세요/こんにちは"));
            }

            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(rule.httpUri("/7/map/get?username=line3&password=armeria3")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("line3/armeria3"));
            }

            httpPost = newHttpPost("/7/map/post", "line4", "armeria4");
            try (CloseableHttpResponse res = hc.execute(httpPost)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("line4/armeria4"));
            }

            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(rule.httpUri("/7/param/default1")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("hello/world/(null)"));
            }

            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(rule.httpUri("/7/param/default1?extra=people")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("hello/world/people"));
            }

            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(rule.httpUri("/7/param/default2")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 400 Bad Request"));
            }

            // Precedence test. (path variable > query string parameter)
            try (CloseableHttpResponse res = hc.execute(new HttpGet(
                    rule.httpUri("/7/param/precedence/line5?username=dot&password=armeria5")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("line5/armeria5"));
            }
        }
    }

    private static HttpPost newHttpPost(String path) {
        return newHttpPost(path, "armeria", "armeria");
    }

    private static HttpPost newHttpPost(String path,
                                        String username, String password) {
        // HTTP.DEF_CONTENT_CHARSET = ISO-8859-1
        return newHttpPost(path, username, password, HTTP.DEF_CONTENT_CHARSET);
    }

    private static HttpPost newHttpPost(String path, String username, String password, Charset charset) {
        HttpPost httpPost = new HttpPost(rule.httpUri(path));

        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("username", username));
        params.add(new BasicNameValuePair("password", password));
        httpPost.setEntity(new UrlEncodedFormEntity(params, charset));

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
