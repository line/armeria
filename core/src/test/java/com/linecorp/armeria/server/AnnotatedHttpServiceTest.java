/*
 * Copyright 2017 LINE Corporation
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
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.TestConverters.NaiveIntConverter;
import com.linecorp.armeria.server.TestConverters.NaiveStringConverter;
import com.linecorp.armeria.server.TestConverters.TypedNumberConverter;
import com.linecorp.armeria.server.TestConverters.TypedStringConverter;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverter;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Converter;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProduceType;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.internal.AnticipatedException;
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

            sb.annotatedService("/8", new MyAnnotatedService8(),
                                LoggingService.newDecorator());

            sb.annotatedService("/9", new MyAnnotatedService9(),
                                LoggingService.newDecorator());

            sb.annotatedService("/10", new MyAnnotatedService10(),
                                LoggingService.newDecorator());

            sb.annotatedService("/11", new MyAnnotatedService11(),
                                      LoggingService.newDecorator());
        }
    };

    @Rule
    public TestWatcher watchman = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            rule.server().config().virtualHosts().forEach(vh -> vh.router().dump(System.err));
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
                                    @Param("username") @Default("hello") String username,
                                    @Param("password") @Default("world") Optional<String> password,
                                    @Param("extra") Optional<String> extra,
                                    @Param("number") Optional<Integer> number) {
            // "extra" might be null because there is no default value specified.
            validateContext(ctx);
            return username + "/" + password.get() + "/" + extra.orElse("(null)") +
                   (number.isPresent() ? "/" + number.get() : "");
        }

        @Get
        @Path("/param/default2")
        public String paramDefault2(RequestContext ctx,
                                    @Param("username") @Default("hello") String username,
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

    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class MyAnnotatedService8 {

        @Get("/same/path")
        public String sharedGet() {
            return "GET";
        }

        @Post("/same/path")
        public String sharedPost() {
            return "POST";
        }

        @Post("/same/path")
        @ConsumeType("application/json")
        public String sharedPostJson() {
            return "POST/JSON";
        }

        @Get("/same/path")
        @ProduceType("application/json")
        public String sharedGetJson() {
            return "GET/JSON";
        }

        @Order(-1)
        @Get("/same/path")
        @ProduceType("text/plain")
        public String sharedGetText() {
            return "GET/TEXT";
        }

        @Post("/same/path")
        @ConsumeType("application/json")
        @ProduceType("application/json")
        public String sharedPostJsonBoth() {
            return "POST/JSON/BOTH";
        }

        // To add one more produce type to the virtual host.
        @Get("/other")
        @ProduceType("application/x-www-form-urlencoded")
        public String other() {
            return "GET/FORM";
        }
    }

    @ProduceType("application/xml")
    @ProduceType("application/json")
    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class MyAnnotatedService9 {

        @Get("/same/path")
        public String get() {
            return "GET";
        }

        @Post("/same/path")
        @ConsumeType("application/xml")
        @ConsumeType("application/json")
        public String post() {
            return "POST";
        }
    }

    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class MyAnnotatedService10 {

        @Get("/syncThrow")
        public String sync() {
            throw new IllegalArgumentException("foo");
        }

        @Get("/asyncThrow")
        public CompletableFuture<String> async() {
            throw new IllegalArgumentException("bar");
        }

        @Get("/asyncThrowWrapped")
        public CompletableFuture<String> asyncThrowWrapped() {
            return CompletableFuture.supplyAsync(() -> {
                throw new IllegalArgumentException("hoge");
            });
        }

        @Get("/syncThrow401")
        public String sync401() {
            throw new HttpResponseException(HttpStatus.UNAUTHORIZED);
        }

        @Get("/asyncThrow401")
        public CompletableFuture<String> async401() {
            throw new HttpResponseException(HttpStatus.UNAUTHORIZED);
        }

        @Get("/asyncThrowWrapped401")
        public CompletableFuture<String> asyncThrowWrapped401() {
            return CompletableFuture.supplyAsync(() -> {
                throw new HttpResponseException(HttpStatus.UNAUTHORIZED);
            });
        }
    }

    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class MyAnnotatedService11 {

        @Get("/aHeader")
        public String aHeader(@Header("if-match") String ifMatch) {
            if ("737060cd8c284d8af7ad3082f209582d".equalsIgnoreCase(ifMatch)) {
                return "matched";
            }
            return "unMatched";
        }

        @Post("/customHeader")
        public String customHeader(@Header("a-name") String name) {
            return name + " is awesome";
        }

        @Get("/headerDefault")
        public String headerDefault(RequestContext ctx,
                                    @Header("username") @Default("hello") String username,
                                    @Header("password") @Default("world") Optional<String> password,
                                    @Header("extra") Optional<String> extra,
                                    @Header("number") Optional<Integer> number) {
            validateContext(ctx);
            return username + "/" + password.get() + "/" + extra.orElse("(null)") +
                   (number.isPresent() ? "/" + number.get() : "");
        }

        @Get("/headerWithParam")
        public String headerWithParam(RequestContext ctx,
                                    @Header("username") @Default("hello") String username,
                                    @Header("password") @Default("world") Optional<String> password,
                                    @Param("extra") Optional<String> extra,
                                    @Param("number") int number) {
            validateContext(ctx);
            return username + "/" + password.get() + "/" + extra.orElse("(null)") + "/" + number;
        }

        @Get
        @Path("/headerWithoutValue")
        public String headerWithoutValue(RequestContext ctx,
                                    @Param("username") @Default("hello") String username,
                                    @Param("password") String password) {
            validateContext(ctx);
            return username + "/" + password;
        }
    }

    @Test
    public void testAnnotatedHttpService() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testBody(hc, get("/1/int/42"), "Integer: 42");
            testBody(hc, get("/1/int-async/42"), "Integer: 43");
            testBody(hc, post("/1/long/42"), "Number[42]");
            testBody(hc, get("/1/string/blah"), "String: blah");

            // Get a requested path as typed string from ServiceRequestContext or HttpRequest
            testBody(hc, get("/1/path/ctx/async/1"), "String[/1/path/ctx/async/1]");
            testBody(hc, get("/1/path/req/async/1"), "String[/1/path/req/async/1]");
            testBody(hc, get("/1/path/ctx/sync/1"), "String[/1/path/ctx/sync/1]");
            testBody(hc, get("/1/path/req/sync/1"), "String[/1/path/req/sync/1]");

            // Illegal parameter.
            testStatusCode(hc, get("/1/int/fourty-two"), 400);
            // Without parameter (non-existing url).
            testStatusCode(hc, post("/1/long/"), 404);
            // Not-mapped HTTP method (Post).
            testStatusCode(hc, post("/1/string/blah"), 405);

            // Exceptions in business logic
            testStatusCode(hc, get("/1/exception/42"), 500);
            testStatusCode(hc, get("/1/exception-async/1"), 500);

            testBody(hc, get("/2/int/42"), "Number[42]");
            testBody(hc, post("/2/long/42"), "Number[42]");
            testBody(hc, get("/2/string/blah"), "String: blah");
            testBody(hc, get("/2/boolean/true"), "String[true]");

            // Illegal parameter.
            testStatusCode(hc, get("/2/int/fourty-two"), 400);
            // Without parameter (non-existing url).
            testStatusCode(hc, post("/2/long/"), 404);
            // Not-mapped HTTP method (Post).
            testStatusCode(hc, post("/2/string/blah"), 405);

            // Test the case where multiple annotated services are bound under the same path prefix.
            testBody(hc, get("/3/int/42"), "Number[42]");
            testBody(hc, get("/3/string/blah"), "String[blah]");
            testBody(hc, get("/3/no-path-param"), "String[no-path-param]");

            testStatusCode(hc, get("/3/undefined"), 404);
        }
    }

    @Test
    public void testNonDefaultPathMappings() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Exact pattern
            testBody(hc, get("/6/exact"), "String[exact:/6/exact]");
            // Prefix pattern
            testBody(hc, get("/6/prefix/foo"), "String[prefix:/6/prefix/foo:/foo]");
            // Glob pattern
            testBody(hc, get("/6/glob1/bar"), "String[glob1:/6/glob1/bar]");
            testBody(hc, get("/6/baz/glob2"), "String[glob2:/6/baz/glob2:0]");
            // Regex pattern
            testBody(hc, get("/6/regex/foo/bar"), "String[regex:/6/regex/foo/bar:foo/bar]");
        }
    }

    @Test
    public void testAggregation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testForm(hc, form("/3/a/string"));
            testForm(hc, form("/3/a/string-async1"));
            testForm(hc, form("/3/a/string-async2"));
            testForm(hc, form("/3/a/string-aggregate-response1"));
            testForm(hc, form("/3/a/string-aggregate-response2"));
        }
    }

    @Test
    public void testParam() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testBody(hc, get("/7/param/get?username=line1&password=armeria1"), "line1/armeria1");
            testBody(hc, form("/7/param/post", StandardCharsets.UTF_8,
                              "username", "line2", "password", "armeria2"), "line2/armeria2");
            testBody(hc, form("/7/param/post", StandardCharsets.UTF_8,
                              "username", "안녕하세요", "password", "こんにちは"), "안녕하세요/こんにちは",
                     StandardCharsets.UTF_8);

            testBody(hc, get("/7/map/get?username=line3&password=armeria3"), "line3/armeria3");
            testBody(hc, form("/7/map/post", null,
                              "username", "line4", "password", "armeria4"), "line4/armeria4");

            testBody(hc, get("/7/param/default1"), "hello/world/(null)");
            testBody(hc, get("/7/param/default1?extra=people&number=1"), "hello/world/people/1");

            // Precedence test. (path variable > query string parameter)
            testBody(hc, get("/7/param/precedence/line5?username=dot&password=armeria5"), "line5/armeria5");

            testStatusCode(hc, get("/7/param/default2"), 400);
        }
    }

    @Test
    public void testAdvancedAnnotatedHttpService() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {

            final String uri = "/8/same/path";

            // No 'Accept' header means accepting everything. The order of -1 would be matched first.
            testBodyAndContentType(hc, get(uri), "GET/TEXT", "text/plain");
            // The same as the above.
            testBodyAndContentType(hc, get(uri, "*/*"), "GET/TEXT", "text/plain");
            testBodyAndContentType(hc, post(uri, "application/json", "application/json"),
                                   "POST/JSON/BOTH", "application/json");
            testBodyAndContentType(hc, get(uri, "application/json;q=0.9, text/plain"),
                                   "GET/TEXT", "text/plain");
            testBodyAndContentType(hc, get(uri, "application/json;q=0.9, text/plain;q=0.7"),
                                   "GET/JSON", "application/json");
            testBodyAndContentType(hc, get(uri, "application/json;charset=UTF-8;q=0.9, text/plain;q=0.7"),
                                   "GET/TEXT", "text/plain");
            testBodyAndContentType(hc, get(uri, "application/x-www-form-urlencoded" +
                                                ",application/json;charset=UTF-8;q=0.9" +
                                                ",text/plain;q=0.7"),
                                   "GET/TEXT", "text/plain");
            testBodyAndContentType(hc, post(uri, "application/json"),
                                   "POST/JSON/BOTH", "application/json");

            testBody(hc, post(uri), "POST");

            // No match on 'Accept' header list.
            testStatusCode(hc, post(uri, null, "application/json"), 406);
            testStatusCode(hc, get(uri, "application/json;charset=UTF-8;q=0.9, text/html;q=0.7"), 406);
        }
    }

    @Test
    public void testServiceThrowIllegalArgumentException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testStatusCode(hc, get("/10/syncThrow"), 400);
            testStatusCode(hc, get("/10/asyncThrow"), 400);
            testStatusCode(hc, get("/10/asyncThrowWrapped"), 400);
        }
    }

    @Test
    public void testServiceThrowHttpResponseException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testStatusCode(hc, get("/10/syncThrow401"), 401);
            testStatusCode(hc, get("/10/asyncThrow401"), 401);
            testStatusCode(hc, get("/10/asyncThrowWrapped401"), 401);
        }
    }

    @Test
    public void testClassScopeMediaTypeAnnotations() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final String uri = "/9/same/path";

            // "application/xml" is matched because "Accept: */*" is specified and
            // the order of @ProduceTypes is {"application/xml", "application/json"}.
            testBodyAndContentType(hc, get(uri, "*/*"),
                                   "GET", "application/xml");
            testBodyAndContentType(hc, post(uri, "application/xml", "*/*"),
                                   "POST", "application/xml");

            // "application/json" is matched because "Accept: application/json" is specified.
            testBodyAndContentType(hc, get(uri, "application/json"),
                                   "GET", "application/json");
            testBodyAndContentType(hc, post(uri, "application/json", "application/json"),
                                   "POST", "application/json");

            testStatusCode(hc, get(uri, "text/plain"), 406);
            testStatusCode(hc, post(uri, "text/plain", "*/*"), 415);
        }
    }

    @Test
    public void testRequestHeaderInjection() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpRequestBase request = get("/11/aHeader");
            request.setHeader(org.apache.http.HttpHeaders.IF_MATCH, "737060cd8c284d8af7ad3082f209582d");
            testBody(hc, request, "matched");

            request = post("/11/customHeader");
            request.setHeader("a-name", "minwoox");
            testBody(hc, request, "minwoox is awesome");

            request = get("/11/headerDefault");
            testBody(hc, request, "hello/world/(null)");

            request = get("/11/headerDefault");
            request.setHeader("extra", "people");
            request.setHeader("number", "1");
            testBody(hc, request, "hello/world/people/1");

            request = get("/11/headerWithParam?extra=people&number=2");
            request.setHeader("username", "trustin");
            request.setHeader("password", "hyangtack");
            testBody(hc, request, "trustin/hyangtack/people/2");

            testStatusCode(hc, get("/11/headerWithoutValue"), 400);
        }
    }

    static void testBodyAndContentType(CloseableHttpClient hc, HttpRequestBase req,
                                       String body, String contentType) throws IOException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, 200, body, null, contentType);
        }
    }

    static void testBody(CloseableHttpClient hc, HttpRequestBase req,
                         String body) throws IOException {
        testBody(hc, req, body, null);
    }

    static void testBody(CloseableHttpClient hc, HttpRequestBase req,
                         String body, @Nullable Charset encoding) throws IOException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, 200, body, encoding, null);
        }
    }

    static void testStatusCode(CloseableHttpClient hc, HttpRequestBase req,
                               int statusCode) throws IOException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, statusCode, null, null, null);
        }
    }

    static void testForm(CloseableHttpClient hc, HttpPost req) throws IOException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, 200, EntityUtils.toString(req.getEntity()), null, null);
        }
    }

    static void checkResult(org.apache.http.HttpResponse res,
                            int statusCode,
                            @Nullable String body,
                            @Nullable Charset encoding,
                            @Nullable String contentType) throws IOException {
        final HttpStatus status = HttpStatus.valueOf(statusCode);
        assertThat(res.getStatusLine().toString(), is("HTTP/1.1 " + status));
        if (body != null) {
            if (encoding != null) {
                assertThat(EntityUtils.toString(res.getEntity(), encoding), is(body));
            } else {
                assertThat(EntityUtils.toString(res.getEntity()), is(body));
            }
        }

        final org.apache.http.Header header = res.getFirstHeader(org.apache.http.HttpHeaders.CONTENT_TYPE);
        if (contentType != null) {
            assertThat(header.getValue(), is(contentType));
        } else if (statusCode >= 400) {
            assertThat(header.getValue(), is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        } else {
            assert header == null;
        }
    }

    static HttpRequestBase get(String uri) {
        return request(HttpMethod.GET, uri, null, null);
    }

    static HttpRequestBase get(String uri, String accept) {
        return request(HttpMethod.GET, uri, null, accept);
    }

    static HttpRequestBase post(String uri) {
        return request(HttpMethod.POST, uri, null, null);
    }

    static HttpRequestBase post(String uri, String contentType) {
        return request(HttpMethod.POST, uri, contentType, null);
    }

    static HttpRequestBase post(String uri, String contentType, String accept) {
        return request(HttpMethod.POST, uri, contentType, accept);
    }

    static HttpPost form(String uri) {
        return form(uri, null, "armeria", "armeria");
    }

    static HttpPost form(String uri, Charset charset, String... kv) {
        final HttpPost req = (HttpPost) request(HttpMethod.POST, uri, MediaType.FORM_DATA.toString());

        final List<NameValuePair> params = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.add(new BasicNameValuePair(kv[i], kv[i + 1]));
        }
        // HTTP.DEF_CONTENT_CHARSET = ISO-8859-1
        final Charset encoding = charset == null ? HTTP.DEF_CONTENT_CHARSET : charset;
        final UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params, encoding);
        req.setEntity(entity);
        return req;
    }

    static HttpRequestBase request(HttpMethod method, String uri, String contentType) {
        return request(method, uri, contentType, null);
    }

    static HttpRequestBase request(HttpMethod method, String uri, String contentType, String accept) {
        final HttpRequestBase req;
        switch (method) {
            case GET:
                req = new HttpGet(rule.httpUri(uri));
                break;
            case POST:
                req = new HttpPost(rule.httpUri(uri));
                break;
            default:
                throw new Error("Unexpected method: " + method);
        }
        if (contentType != null) {
            req.setHeader(org.apache.http.HttpHeaders.CONTENT_TYPE, contentType);
        }
        if (accept != null) {
            req.setHeader(org.apache.http.HttpHeaders.ACCEPT, accept);
        }
        return req;
    }

    static void validateContext(RequestContext ctx) {
        if (RequestContext.current() != ctx) {
            throw new RuntimeException("ServiceRequestContext instances are not same!");
        }
    }

    static void validateContextAndRequest(RequestContext ctx, Object req) {
        validateContext(ctx);
        if (RequestContext.current().request() != req) {
            throw new RuntimeException("HttpRequest instances are not same!");
        }
    }
}
