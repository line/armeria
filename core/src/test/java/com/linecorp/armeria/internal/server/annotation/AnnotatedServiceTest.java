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
package com.linecorp.armeria.internal.server.annotation;

import static org.apache.hc.core5.http.HttpHeaders.ACCEPT;
import static org.apache.hc.core5.http.HttpHeaders.CONTENT_TYPE;
import static org.apache.hc.core5.http.HttpHeaders.IF_MATCH;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TestConverters.NaiveIntConverterFunction;
import com.linecorp.armeria.server.TestConverters.NaiveStringConverterFunction;
import com.linecorp.armeria.server.TestConverters.TypedNumberConverterFunction;
import com.linecorp.armeria.server.TestConverters.TypedStringConverterFunction;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Delimiter;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Mono;

@GenerateNativeImageTrace
class AnnotatedServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Case 1, 2, and 3, with a converter map
            sb.annotatedService("/1", new MyAnnotatedService1(),
                                LoggingService.newDecorator(),
                                new TypedNumberConverterFunction());

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

            sb.annotatedService("/12", new MyAnnotatedService12(),
                                LoggingService.newDecorator());

            sb.annotatedService("/13", new MyAnnotatedService13(),
                                LoggingService.newDecorator());

            sb.annotatedService("/14", new MyAnnotatedService14(),
                                LoggingService.newDecorator());

            sb.annotatedService()
              .pathPrefix("/15")
              .queryDelimiter(",")
              .decorator(LoggingService.newDecorator())
              .build(new MyAnnotatedService14());

            sb.annotatedService()
              .pathPrefix("/16")
              .queryDelimiter(":")
              .decorator(LoggingService.newDecorator())
              .build(new MyAnnotatedService14());

            sb.annotatedService("/17", new MyAnnotatedService15(),
                                LoggingService.newDecorator());
        }
    };

    @ResponseConverter(NaiveIntConverterFunction.class)
    @ResponseConverter(TypedStringConverterFunction.class)
    public static class MyAnnotatedService1 {
        // Case 1: returns Integer type and handled by builder-default Integer -> HttpResponse converter.
        @Get
        @Path("/int/:var")
        public int returnInt(@Param int var) {
            return var;
        }

        // Case 2: returns Long type and handled by class-default Number -> HttpResponse converter.
        @Post
        @Path("/long/{var}")
        public CompletionStage<Long> returnLong(@Param long var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Case 3: returns String type and handled by custom String -> HttpResponse converter.
        @Get
        @Path("/string/:var")
        @ResponseConverter(NaiveStringConverterFunction.class)
        public CompletionStage<String> returnString(@Param String var) {
            return CompletableFuture.supplyAsync(() -> var);
        }

        // Asynchronously returns Integer type and handled by builder-default Integer -> HttpResponse converter.
        @Get
        @Path("/int-async/:var")
        public CompletableFuture<Integer> returnIntAsync(@Param int var) {
            return UnmodifiableFuture.completedFuture(var).thenApply(n -> n + 1);
        }

        @Get
        @Path("/string-response-async/:var")
        public CompletableFuture<HttpResponse> returnStringResponseAsync(@Param String var) {
            return CompletableFuture.supplyAsync(() -> HttpResponse.of(var));
        }

        // Wrapped content is handled by a custom String -> HttpResponse converter.
        @Get
        @Path("/string-response-entity-async/:var")
        @ResponseConverter(NaiveStringConverterFunction.class)
        public CompletableFuture<ResponseEntity<String>> returnStringResultAsync(@Param String var) {
            return CompletableFuture.supplyAsync(() -> ResponseEntity.of(var));
        }

        @Get
        @Path("/path/ctx/async/:var")
        public static CompletableFuture<String> returnPathCtxAsync(@Param int var,
                                                                   ServiceRequestContext ctx,
                                                                   Request req) {
            validateContextAndRequest(ctx, req);
            return UnmodifiableFuture.completedFuture(ctx.path());
        }

        @Get
        @Path("/path/req/async/:var")
        public static CompletableFuture<String> returnPathReqAsync(@Param int var,
                                                                   HttpRequest req,
                                                                   ServiceRequestContext ctx) {
            validateContextAndRequest(ctx, req);
            return UnmodifiableFuture.completedFuture(req.path());
        }

        @Get
        @Path("/path/ctx/sync/:var")
        public static String returnPathCtxSync(@Param int var,
                                               RequestContext ctx,
                                               Request req) {
            validateContextAndRequest(ctx, req);
            return ctx.path();
        }

        @Get
        @Path("/path/req/sync/:var")
        public static String returnPathReqSync(@Param int var,
                                               HttpRequest req,
                                               RequestContext ctx) {
            validateContextAndRequest(ctx, req);
            return req.path();
        }

        // Throws an exception synchronously
        @Get
        @Path("/exception/:var")
        public int exception(@Param int var) {
            throw new AnticipatedException("bad var!");
        }

        // Throws an exception asynchronously
        @Get
        @Path("/exception-async/:var")
        public CompletableFuture<Integer> exceptionAsync(@Param int var) {
            final CompletableFuture<Integer> future = new CompletableFuture<>();
            future.completeExceptionally(new AnticipatedException("bad var!"));
            return future;
        }

        // Log warning.
        @Get("/warn/:var")
        public String warn() {
            return "warn";
        }

        @Get("/void/204")
        public void void204() {}

        @Get("/void/200")
        @ResponseConverter(VoidTo200ResponseConverter.class)
        public void void200() {}

        @Get("/void/produces/204")
        @ProducesJson
        public void voidProduces204() {}

        @Get("/void/json/204")
        @StatusCode(204)
        public void voidJson204() {}

        @Get("/voidPublisher/204")
        public Publisher<Void> voidPublisher204() {
            return Mono.empty();
        }

        @Get("/voidPublisher/200")
        @ResponseConverter(VoidTo200ResponseConverter.class)
        public Publisher<Void> voidPublisher200() {
            return Mono.empty();
        }

        @Get("/voidPublisher/produces/204")
        @ProducesJson
        public Publisher<Void> voidPublisherProduces204() {
            return Mono.empty();
        }

        @Get("/voidPublisher/json/204")
        @StatusCode(204)
        public Publisher<Void> voidPublisherJson204() {
            return Mono.empty();
        }

        @Get("/voidFuture/204")
        public CompletionStage<Void> voidFuture204() {
            return UnmodifiableFuture.completedFuture(null);
        }

        @Get("/voidFuture/200")
        @ResponseConverter(VoidTo200ResponseConverter.class)
        public CompletionStage<Void> voidFuture200() {
            return UnmodifiableFuture.completedFuture(null);
        }

        @Get("/voidFuture/produces/204")
        @ProducesJson
        public CompletionStage<Void> voidFutureProduces204() {
            return UnmodifiableFuture.completedFuture(null);
        }

        @Get("/voidFuture/json/204")
        @StatusCode(204)
        public CompletionStage<Void> voidFutureJson204() {
            return UnmodifiableFuture.completedFuture(null);
        }

        @Get("/verb/test:verb")
        public String verbTestExact() {
            return "/verb/test:verb";
        }

        @Get("/verb/:param")
        public String noVerbTest(@Param String param) {
            return "no-verb";
        }

        @Get("/\\:colon:literal:/:param")
        public String colonLiteralParam(@Param String param) {
            return "colon-literal-" + param;
        }

        @Get("/\\:colon:literal:exact:implicit")
        public String colonLiteralExactImplicit() {
            return "colon-literal-exact-implicit";
        }

        @Get("exact:/\\:colon:literal:exact:explicit")
        public String colonLiteralExactExplicit() {
            return "colon-literal-exact-explicit";
        }

        @Get("glob:/:colon:literal:glob:/**")
        public String colonLiteralGlob() {
            return "colon-literal-glob";
        }

        @Get("regex:/:colon:literal:regex:/(?<param>[^/]+)$")
        public String colonLiteralRegex(@Param String param) {
            return "colon-literal-" + param;
        }
    }

    static class VoidTo200ResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result == null) {
                return HttpResponse.of(HttpStatus.OK);
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    @ResponseConverter(TypedNumberConverterFunction.class)
    @ResponseConverter(TypedStringConverterFunction.class)
    @ResponseConverter(UnformattedStringConverterFunction.class)
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
        @ResponseConverter(NaiveStringConverterFunction.class)
        public String returnString(@Param("var") String var) {
            return var;
        }

        @Get
        @Path("/boolean/{var}")
        public String returnBoolean(@Param("var") boolean var) {
            return Boolean.toString(var);
        }

        @Nullable
        @Get("/null1")
        public Object returnNull1() {
            return null;
        }

        @Get("/null2")
        public CompletionStage<Object> returnNull2() {
            return UnmodifiableFuture.completedFuture(null);
        }
    }

    @ResponseConverter(TypedNumberConverterFunction.class)
    public static class MyAnnotatedService3 {
        @Get
        @Path("/int/{var}")
        public CompletionStage<Integer> returnInt(@Param("var") int var) {
            return CompletableFuture.supplyAsync(() -> var);
        }
    }

    @ResponseConverter(TypedStringConverterFunction.class)
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
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService5 {
        @Post
        @Path("/a/string")
        public String postString(AggregatedHttpRequest request, RequestContext ctx) {
            validateContext(ctx);
            return request.contentUtf8();
        }

        @Post
        @Path("/a/string-async1")
        public CompletionStage<String> postStringAsync1(AggregatedHttpRequest request, RequestContext ctx) {
            validateContext(ctx);
            return CompletableFuture.supplyAsync(request::contentUtf8);
        }

        @Post
        @Path("/a/string-async2")
        public HttpResponse postStringAsync2(AggregatedHttpRequest request, RequestContext ctx) {
            validateContext(ctx);
            final HttpResponseWriter response = HttpResponse.streaming();
            response.write(ResponseHeaders.of(HttpStatus.OK));
            response.write(request.content());
            response.close();
            return response;
        }

        @Post
        @Path("/a/string-aggregate-response1")
        public AggregatedHttpResponse postStringAggregateResponse1(AggregatedHttpRequest request,
                                                                   RequestContext ctx) {
            validateContext(ctx);
            return AggregatedHttpResponse.of(ResponseHeaders.of(HttpStatus.OK), request.content());
        }
    }

    /**
     * An annotated service that's used for testing non-default path mappings.
     */
    @ResponseConverter(TypedStringConverterFunction.class)
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

    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService7 {

        @Get("/param/get")
        public String paramGet(RequestContext ctx,
                               @Param("username") String username,
                               @Param("password") String password) {
            validateContext(ctx);
            return username + '/' + password;
        }

        @Post("/param/post")
        public String paramPost(RequestContext ctx,
                                @Param("username") String username,
                                @Param("password") String password) {
            validateContext(ctx);
            return username + '/' + password;
        }

        @Get
        @Path("/map/get")
        public String mapGet(RequestContext ctx, QueryParams params) {
            validateContext(ctx);
            return params.get("username") + '/' + params.get("password");
        }

        @Post
        @Path("/map/post")
        public String mapPost(RequestContext ctx, QueryParams params) {
            validateContext(ctx);
            return params.get("username") + '/' + params.get("password");
        }

        @Get("/param/enum")
        public String paramEnum(RequestContext ctx,
                                @Param("username") String username,
                                @Param("level") UserLevel level) {
            validateContext(ctx);
            return username + '/' + level;
        }

        @Get("/param/enum2")
        public String paramEnum2(RequestContext ctx,
                                 @Param("type") UserType type,
                                 @Param("level") UserLevel level) {
            validateContext(ctx);
            return type + "/" + level;
        }

        @Get("/param/enum3")
        public String paramEnum3(RequestContext ctx,
                                 @Param("type") List<UserType> types,
                                 @Param("level") Set<UserLevel> levels) {
            validateContext(ctx);
            return ImmutableList.builder().addAll(types).addAll(levels).build().stream()
                                .map(e -> ((Enum<?>) e).name())
                                .collect(Collectors.joining("/"));
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
            return username + '/' + password.get() + '/' + extra.orElse("(null)") +
                   (number.isPresent() ? "/" + number.get() : "");
        }

        @Get
        @Path("/param/default2")
        public String paramDefault2(RequestContext ctx,
                                    @Param("username") @Default("hello") String username,
                                    @Param("password") String password) {
            validateContext(ctx);
            return username + '/' + password;
        }

        @Get
        @Path("/param/default_null")
        public String paramDefaultNull(RequestContext ctx, @Param @Default String value) {
            validateContext(ctx);
            return value;
        }

        @Get
        @Path("/param/precedence/{username}")
        public String paramPrecedence(RequestContext ctx,
                                      @Param("username") String username,
                                      @Param("password") String password) {
            validateContext(ctx);
            return username + '/' + password;
        }
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService8 {

        @Get("/same/path")
        public String sharedGet() {
            return "GET";
        }

        @Post("/same/path")
        public String sharedPost() {
            return "POST";
        }

        @Get("/same/path")
        @Produces("application/json")
        public String sharedGetJson() {
            return "GET/JSON";
        }

        @Post("/same/path")
        @Consumes("application/json")
        public String sharedPostJson() {
            return "POST/JSON";
        }

        @Order(-1)
        @Get("/same/path")
        @Produces("text/plain")
        public String sharedGetText() {
            return "GET/TEXT";
        }

        @Post("/same/path")
        @Consumes("application/json")
        @Produces("application/json")
        public String sharedPostJsonBoth() {
            return "POST/JSON/BOTH";
        }

        // To add one more produce type to the virtual host.
        @Get("/other")
        @Produces("application/x-www-form-urlencoded")
        public String other() {
            return "GET/FORM";
        }
    }

    @Produces("application/xml")
    @Produces("application/json")
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService9 {

        @Get("/same/path")
        public String get() {
            return "GET";
        }

        @Post("/same/path")
        @Consumes("application/xml")
        @Consumes("application/json")
        public String post() {
            return "POST";
        }
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
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
            throw HttpStatusException.of(HttpStatus.UNAUTHORIZED);
        }

        @Get("/asyncThrow401")
        public CompletableFuture<String> async401() {
            throw HttpStatusException.of(HttpStatus.UNAUTHORIZED);
        }

        @Get("/asyncThrowWrapped401")
        public CompletableFuture<String> asyncThrowWrapped401() {
            return CompletableFuture.supplyAsync(() -> {
                throw HttpStatusException.of(HttpStatus.UNAUTHORIZED);
            });
        }
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService11 {

        @Get("/aHeader")
        public String aHeader(@Header String ifMatch) {
            if ("737060cd8c284d8af7ad3082f209582d".equalsIgnoreCase(ifMatch)) {
                return "matched";
            }
            return "unMatched";
        }

        @Post("/customHeader1")
        public String customHeader1(@Header List<String> aName) {
            return String.join(":", aName) + " is awesome";
        }

        @Post("/customHeader2")
        public String customHeader2(@Header Set<String> aName) {
            return String.join(":", aName) + " is awesome";
        }

        @Post("/customHeader3")
        public String customHeader3(@Header LinkedList<String> aName) {
            return String.join(":", aName) + " is awesome";
        }

        @Post("/customHeader4")
        public String customHeader3(@Header TreeSet<String> aName) {
            return String.join(":", aName) + " is awesome";
        }

        @Post("/customHeader5")
        public String customHeader5(@Header List<Integer> numbers,
                                    @Header Set<String> strings) {
            return numbers.stream()
                          .map(String::valueOf)
                          .collect(Collectors.joining(":")) + '/' +
                   String.join(":", strings);
        }

        @Get("/headerDefault")
        public String headerDefault(RequestContext ctx,
                                    @Header @Default("hello") String username,
                                    @Header @Default("world") Optional<String> password,
                                    @Header Optional<String> extra,
                                    @Header Optional<Integer> number) {
            validateContext(ctx);
            return username + '/' + password.get() + '/' + extra.orElse("(null)") +
                   (number.isPresent() ? "/" + number.get() : "");
        }

        @Get("/headerWithParam")
        public String headerWithParam(RequestContext ctx,
                                      @Header("username") @Default("hello") String username,
                                      @Header("password") @Default("world") Optional<String> password,
                                      @Param("extra") Optional<String> extra,
                                      @Param("number") int number) {
            validateContext(ctx);
            return username + '/' + password.get() + '/' + extra.orElse("(null)") + '/' + number;
        }

        @Get
        @Path("/headerWithoutValue")
        public String headerWithoutValue(RequestContext ctx,
                                         @Header("username") @Default("hello") String username,
                                         @Header("password") String password) {
            validateContext(ctx);
            return username + '/' + password;
        }
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService12 {

        @Get
        @Path("/pathMapping1")
        @Path("/pathMapping2")
        public String pathMapping(RequestContext ctx) {
            return "multiGet";
        }

        @Get
        @Path("/duplicatePath")
        @Path("/duplicatePath")
        public String duplicatePath(RequestContext ctx) {
            return "duplicatePath";
        }

        @Get
        @Path("/pathSameParam1/{param}")
        @Path("/pathSameParam2/{param}")
        public String pathSameParam(RequestContext ctx, @Param String param) {
            return param;
        }

        @Get
        @Path("/pathDiffParam1/{param1}")
        @Path("/pathDiffParam2/{param2}")
        public String pathDiffParam(RequestContext ctx, @Param String param1, @Param String param2) {
            return param1 + '_' + param2;
        }

        @Get
        @Path("/pathDiffPattern/path")
        @Path("/pathDiffPattern/{param}")
        public String pathDiffPattern(@Param @Default("default") String param) {
            return param;
        }

        @Get
        @Post
        @Path("/getPostWithPathMapping1")
        @Path("/getPostWithPathMapping2")
        public String getPostWithPathMapping(RequestContext ctx) {
            return ctx.path();
        }

        @Get("/getMapping")
        @Post("/postMapping")
        public String getPostMapping(RequestContext ctx) {
            return ctx.path();
        }
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService13 {

        @Get("/wildcard1")
        public String wildcard(@Param List<? extends String> param) {
            return String.join(":", param);
        }

        @Get("/wildcard2")
        public <T extends String> String wildcard2(@Param List<T> param) {
            return String.join(":", param);
        }
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyAnnotatedService14 {

        @Get("/param/multi")
        public String multiParams(RequestContext ctx, @Param("params") List<String> params) {
            validateContext(ctx);
            return String.join("/", params);
        }

        @Get("/param/multiWithDelimiter")
        public String multiParamsWithDelimiter(RequestContext ctx,
                                               @Param("params") @Delimiter("$") List<String> params) {
            validateContext(ctx);
            return String.join("/", params);
        }
    }

    @ResponseConverter(NaiveStringConverterFunction.class)
    public static class MyAnnotatedService15 {
        @Get
        @Path("/response-entity-void")
        public ResponseEntity<Void> responseEntityVoid(RequestContext ctx) {
            validateContext(ctx);
            return ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK));
        }

        @Get
        @Path("/response-entity-string/{name}")
        public ResponseEntity<String> responseEntityString(RequestContext ctx, @Param("name") String name) {
            validateContext(ctx);
            return ResponseEntity.of(name);
        }

        @Get
        @Path("/response-entity-status")
        public ResponseEntity<Void> responseEntityResponseData(RequestContext ctx) {
            validateContext(ctx);
            return ResponseEntity.of(ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY));
        }

        @Get
        @Path("/response-entity-http-response")
        public ResponseEntity<HttpResponse> responseEntityHttpResponse(RequestContext ctx) {
            validateContext(ctx);
            return ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK),
                                     HttpResponse.of(HttpStatus.UNAUTHORIZED));
        }
    }

    @Test
    void testAnnotatedService() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testBody(hc, get("/1/int/42"), "Integer: 42");
            testBody(hc, get("/1/int-async/42"), "Integer: 43");
            testBody(hc, post("/1/long/42"), "Number[42]");
            testBody(hc, get("/1/string/blah"), "String: blah");
            testBody(hc, get("/1/string/%F0%90%8D%88"), "String: \uD800\uDF48", // 𐍈
                     StandardCharsets.UTF_8);

            // Deferred HttpResponse and ResponseEntity.
            testBody(hc, get("/1/string-response-async/blah"), "blah");
            testBody(hc, get("/1/string-response-entity-async/blah"), "String: blah");

            // Get a requested path as typed string from ServiceRequestContext or HttpRequest
            testBody(hc, get("/1/path/ctx/async/1"), "String[/1/path/ctx/async/1]");
            testBody(hc, get("/1/path/req/async/1"), "String[/1/path/req/async/1]");
            testBody(hc, get("/1/path/ctx/sync/1"), "String[/1/path/ctx/sync/1]");
            testBody(hc, get("/1/path/req/sync/1"), "String[/1/path/req/sync/1]");

            // Illegal parameter.
            testStatusCode(hc, get("/1/int/forty-two"), 400);
            // Without parameter (non-existing url).
            testStatusCode(hc, post("/1/long/"), 404);
            // Not-mapped HTTP method (Post).
            testStatusCode(hc, post("/1/string/blah"), 405);

            // Exceptions in business logic
            testStatusCode(hc, get("/1/exception/42"), 500);
            testStatusCode(hc, get("/1/exception-async/1"), 500);

            // colon in a path
            testBody(hc, get("/1/verb/test:verb"), "String[/verb/test:verb]");
            testBody(hc, get("/1/verb/test:no-verb"), "String[no-verb]");
            testBody(hc, get("/1/:colon:literal:/hello"), "String[colon-literal-hello]");
            testBody(hc, get("/1/:colon:literal:exact:implicit"), "String[colon-literal-exact-implicit]");
            testBody(hc, get("/1/:colon:literal:exact:explicit"), "String[colon-literal-exact-explicit]");
            testBody(hc, get("/1/:colon:literal:glob:/a/b/c"), "String[colon-literal-glob]");
            testBody(hc, get("/1/:colon:literal:regex:/regex"), "String[colon-literal-regex]");

            testBody(hc, get("/2/int/42"), "Number[42]");
            testBody(hc, post("/2/long/42"), "Number[42]");
            testBody(hc, get("/2/string/blah"), "String: blah");
            testBody(hc, get("/2/boolean/true"), "String[true]");
            testBody(hc, get("/2/boolean/1"), "String[true]");
            testBody(hc, get("/2/boolean/false"), "String[false]");
            testBody(hc, get("/2/boolean/0"), "String[false]");

            // Illegal parameter.
            testStatusCode(hc, get("/2/int/forty-two"), 400);
            testStatusCode(hc, get("/2/boolean/maybe"), 400);
            // Without parameter (non-existing url).
            testStatusCode(hc, post("/2/long/"), 404);
            // Not-mapped HTTP method (Post).
            testStatusCode(hc, post("/2/string/blah"), 405);

            testBody(hc, get("/2/null1"), "(null)");
            testBody(hc, get("/2/null2"), "(null)");

            // Test the case where multiple annotated services are bound under the same path prefix.
            testBody(hc, get("/3/int/42"), "Number[42]");
            testBody(hc, get("/3/string/blah"), "String[blah]");
            testBody(hc, get("/3/no-path-param"), "String[no-path-param]");

            testStatusCode(hc, get("/3/undefined"), 404);
        }
    }

    @Test
    void testNonDefaultRoute() throws Exception {
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
    void testAggregation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testForm(hc, form("/3/a/string"));
            testForm(hc, form("/3/a/string-async1"));
            testForm(hc, form("/3/a/string-async2"));
            testForm(hc, form("/3/a/string-aggregate-response1"));
        }
    }

    @Test
    void testParam() throws Exception {
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

            testBody(hc, get("/7/param/enum?username=line5&level=LV1"), "line5/LV1");
            // Case insensitive test for enum element
            testBody(hc, get("/7/param/enum?username=line5&level=lv1"), "line5/LV1");
            testBody(hc, get("/7/param/enum?username=line6&level=Lv2"), "line6/LV2");
            testStatusCode(hc, get("/7/param/enum?username=line6&level=TEST3"), 400);

            // Case sensitive test enum
            testBody(hc, get("/7/param/enum2?type=normal&level=LV1"), "normal/LV1");
            testBody(hc, get("/7/param/enum2?type=NORMAL&level=LV1"), "NORMAL/LV1");
            testStatusCode(hc, get("/7/param/enum2?type=MINOOX&level=LV1"), 400);

            // Case sensitive test enum
            testBody(hc, get("/7/param/enum3?type=normal&level=LV1&level=LV1"), "normal/LV1");
            testBody(hc, get("/7/param/enum3?type=NORMAL&type=NORMAL&level=LV1"), "NORMAL/NORMAL/LV1");
            testStatusCode(hc, get("/7/param/enum3?type=BAD&level=LV100"), 400);

            testBody(hc, get("/7/param/default1"), "hello/world/(null)");
            testBody(hc, get("/7/param/default1?extra=people&number=1"), "hello/world/people/1");

            // Precedence test. (path variable > query string parameter)
            testBody(hc, get("/7/param/precedence/line5?username=dot&password=armeria5"), "line5/armeria5");

            testStatusCode(hc, get("/7/param/default2"), 400);

            testBody(hc, get("/7/param/default_null"), "(null)");
        }
    }

    @Test
    void testAdvancedAnnotatedService() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final String path = "/8/same/path";

        RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, path);
        AggregatedHttpResponse res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isNull();
        assertThat(res.contentUtf8()).isEqualTo("GET");

        // The same as the above.
        headers = RequestHeaders.of(HttpMethod.GET, path, HttpHeaderNames.ACCEPT, "*/*");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).hasToString("text/plain");
        assertThat(res.contentUtf8()).isEqualTo("GET/TEXT");

        headers = RequestHeaders.of(HttpMethod.GET, path,
                                    HttpHeaderNames.ACCEPT, "application/json;q=0.9, text/plain");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).hasToString("text/plain");
        assertThat(res.contentUtf8()).isEqualTo("GET/TEXT");

        headers = RequestHeaders.of(HttpMethod.GET, path,
                                    HttpHeaderNames.ACCEPT, "application/json;q=0.9, text/plain;q=0.7");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isSameAs(MediaType.JSON);
        assertThat(res.contentUtf8()).isEqualTo("GET/JSON");

        // Because of the charset, sharedGetJson() is not matched.
        headers = RequestHeaders.of(HttpMethod.GET, path,
                                    HttpHeaderNames.ACCEPT,
                                    "application/json;charset=UTF-8;q=0.9, text/plain;q=0.7");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).hasToString("text/plain");
        assertThat(res.contentUtf8()).isEqualTo("GET/TEXT");

        // Because of the charset, sharedGetJson() is not matched.
        headers = RequestHeaders.of(HttpMethod.GET, path,
                                    HttpHeaderNames.ACCEPT,
                                    "application/json;charset=UTF-8;q=0.9, text/html;q=0.7");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isNull();
        assertThat(res.contentUtf8()).isEqualTo("GET");

        headers = RequestHeaders.of(HttpMethod.GET, path,
                                    HttpHeaderNames.ACCEPT,
                                    "application/x-www-form-urlencoded, " +
                                    "application/json;charset=UTF-8;q=0.9, text/plain;q=0.7");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).hasToString("text/plain");
        assertThat(res.contentUtf8()).isEqualTo("GET/TEXT");

        headers = RequestHeaders.of(HttpMethod.POST, path,
                                    HttpHeaderNames.ACCEPT, "application/json",
                                    HttpHeaderNames.CONTENT_TYPE, "application/json");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isSameAs(MediaType.JSON);
        assertThat(res.contentUtf8()).isEqualTo("POST/JSON/BOTH");

        headers = RequestHeaders.of(HttpMethod.POST, path,
                                    HttpHeaderNames.CONTENT_TYPE, "application/json");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isNull();
        assertThat(res.contentUtf8()).isEqualTo("POST/JSON");

        headers = RequestHeaders.of(HttpMethod.POST, path,
                                    HttpHeaderNames.ACCEPT, "application/json");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isSameAs(MediaType.JSON);
        assertThat(res.contentUtf8()).isEqualTo("POST/JSON/BOTH");

        headers = RequestHeaders.of(HttpMethod.POST, path);
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isNull();
        assertThat(res.contentUtf8()).isEqualTo("POST");

        headers = RequestHeaders.of(HttpMethod.POST, path,
                                    HttpHeaderNames.ACCEPT, "test/json",
                                    HttpHeaderNames.CONTENT_TYPE, "application/json");
        res = client.execute(headers);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().contentType()).isNull();
        assertThat(res.contentUtf8()).isEqualTo("POST/JSON");
    }

    @Test
    void testServiceThrowIllegalArgumentException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testStatusCode(hc, get("/10/syncThrow"), 400);
            testStatusCode(hc, get("/10/asyncThrow"), 400);
            testStatusCode(hc, get("/10/asyncThrowWrapped"), 400);
        }
    }

    @Test
    void testServiceThrowHttpResponseException() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testStatusCode(hc, get("/10/syncThrow401"), 401);
            testStatusCode(hc, get("/10/asyncThrow401"), 401);
            testStatusCode(hc, get("/10/asyncThrowWrapped401"), 401);
        }
    }

    @Test
    void testClassScopeMediaTypeAnnotations() throws Exception {
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
    void testRequestHeaderInjection() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpUriRequestBase request = get("/11/aHeader");
            request.setHeader(IF_MATCH, "737060cd8c284d8af7ad3082f209582d");
            testBody(hc, request, "matched");

            request = post("/11/customHeader1");
            request.setHeader("a-name", "minwoox");
            testBody(hc, request, "minwoox is awesome");

            for (int i = 1; i < 4; i++) {
                request = post("/11/customHeader" + i);
                request.addHeader("a-name", "minwoox");
                request.addHeader("a-name", "giraffe");
                testBody(hc, request, "minwoox:giraffe is awesome");
            }

            request = post("/11/customHeader4");
            request.addHeader("a-name", "minwoox");
            request.addHeader("a-name", "giraffe");
            testBody(hc, request, "giraffe:minwoox is awesome");

            request = post("/11/customHeader5");
            request.addHeader("numbers", "1");
            request.addHeader("numbers", "2");
            request.addHeader("numbers", "1");
            request.addHeader("strings", "minwoox");
            request.addHeader("strings", "giraffe");
            request.addHeader("strings", "minwoox");
            testBody(hc, request, "1:2:1/minwoox:giraffe");

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

    @ParameterizedTest
    @ValueSource(strings = { "void", "voidPublisher", "voidFuture" })
    void testReturnVoid(String returnType) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testStatusCode(hc, get("/1/" + returnType + "/204"), 204);
            testBodyAndContentType(hc, get("/1/" + returnType + "/200"),
                                   "200 OK", MediaType.PLAIN_TEXT_UTF_8.toString());
            testStatusCode(hc, get("/1/" + returnType + "/produces/204"), 204);
            testStatusCode(hc, get("/1/" + returnType + "/json/204"), 204);
        }
    }

    @Test
    public void testMultiplePaths() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testStatusCode(hc, get("/12/pathMapping1"), 200);
            testStatusCode(hc, get("/12/pathMapping2"), 200);

            testStatusCode(hc, get("/12/duplicatePath"), 200);

            testBody(hc, get("/12/pathSameParam1/param"), "param");
            testBody(hc, get("/12/pathSameParam2/param"), "param");

            testStatusCode(hc, get("/12/pathDiffParam1/param1"), 400);
            testBody(hc, get("/12/pathDiffParam1/param1?param2=param2"), "param1_param2");
            testStatusCode(hc, get("/12/pathDiffParam2/param2"), 400);
            testBody(hc, get("/12/pathDiffParam2/param2?param1=param1"), "param1_param2");

            testBody(hc, get("/12/pathDiffPattern/path"), "default");
            testBody(hc, get("/12/pathDiffPattern/customArg"), "customArg");

            testBody(hc, get("/12/getPostWithPathMapping1"), "/12/getPostWithPathMapping1");
            testBody(hc, post("/12/getPostWithPathMapping1"), "/12/getPostWithPathMapping1");
            testBody(hc, get("/12/getPostWithPathMapping2"), "/12/getPostWithPathMapping2");
            testBody(hc, post("/12/getPostWithPathMapping2"), "/12/getPostWithPathMapping2");

            testBody(hc, get("/12/getMapping"), "/12/getMapping");
            testStatusCode(hc, post("/12/getMapping"), 405);
            testStatusCode(hc, get("/12/postMapping"), 405);
            testBody(hc, post("/12/postMapping"), "/12/postMapping");
        }
    }

    @Test
    void testWildcard() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testBody(hc, get("/13/wildcard1?param=Hello&param=World"), "Hello:World");
            testBody(hc, get("/13/wildcard2?param=Hello&param=World"), "Hello:World");
        }
    }

    @Test
    void testMultiParams() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testBody(hc, get("/14/param/multi?params=a&params=b&params=c"), "a/b/c");
            testBody(hc, get("/15/param/multi?params=a&params=b&params=c"), "a/b/c");
            testBody(hc, get("/16/param/multi?params=a&params=b&params=c"), "a/b/c");

            testBody(hc, get("/14/param/multi?params=a,b,c"), "a,b,c");
            testBody(hc, get("/15/param/multi?params=a,b,c"), "a/b/c");
            testBody(hc, get("/16/param/multi?params=a:b:c"), "a/b/c");

            testBody(hc, get("/14/param/multi?params=a"), "a");
            testBody(hc, get("/15/param/multi?params=a"), "a");
            testBody(hc, get("/16/param/multi?params=a"), "a");

            testBody(hc, get("/14/param/multi?params=a,b,c&params=d,e,f"), "a,b,c/d,e,f");
            testBody(hc, get("/15/param/multi?params=a,b,c&params=d,e,f"), "a,b,c/d,e,f");
            testBody(hc, get("/16/param/multi?params=a:b:c&params=d:e:f"), "a:b:c/d:e:f");
        }
    }

    @Test
    void testMultiParamsWithDelimiter() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testBody(hc, get("/14/param/multiWithDelimiter?params=a&params=b&params=c"), "a/b/c");
            testBody(hc, get("/15/param/multiWithDelimiter?params=a&params=b&params=c"), "a/b/c");

            testBody(hc, get("/14/param/multiWithDelimiter?params=a,b,c"), "a,b,c");
            testBody(hc, get("/14/param/multiWithDelimiter?params=a$b$c"), "a/b/c");
            testBody(hc, get("/15/param/multiWithDelimiter?params=a,b,c"), "a,b,c");
            testBody(hc, get("/15/param/multiWithDelimiter?params=a$b$c"), "a/b/c");

            testBody(hc, get("/14/param/multiWithDelimiter?params=a"), "a");
            testBody(hc, get("/15/param/multiWithDelimiter?params=a"), "a");

            testBody(hc, get("/14/param/multiWithDelimiter?params=a,b,c&params=d,e,f"), "a,b,c/d,e,f");
            testBody(hc, get("/14/param/multiWithDelimiter?params=a$b$c&params=d$e$f"), "a$b$c/d$e$f");
            testBody(hc, get("/15/param/multiWithDelimiter?params=a,b,c&params=d,e,f"), "a,b,c/d,e,f");
            testBody(hc, get("/15/param/multiWithDelimiter?params=a$b$c&params=d$e$f"), "a$b$c/d$e$f");
        }
    }

    @Test
    void testResponseEntity() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testBody(hc, get("/17/response-entity-void"), null);
            testBody(hc, get("/17/response-entity-string/test"), "String: test");
            testStatusCode(hc, get("/17/response-entity-status"), 301);
            testStatusCode(hc, get("/17/response-entity-http-response"), 401);
        }
    }

    private enum UserLevel {
        LV1,
        LV2
    }

    private enum UserType {
        normal,
        NORMAL
    }

    static void testBodyAndContentType(CloseableHttpClient hc, HttpUriRequestBase req,
                                       String body, String contentType) throws IOException, ParseException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, 200, body, null, contentType);
        }
    }

    static void testBody(CloseableHttpClient hc, HttpUriRequestBase req,
                         String body) throws IOException, ParseException {
        testBody(hc, req, body, null);
    }

    static void testBody(CloseableHttpClient hc, HttpUriRequestBase req,
                         String body, @Nullable Charset encoding) throws IOException, ParseException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, 200, body, encoding, null);
        }
    }

    static void testStatusCode(CloseableHttpClient hc, HttpUriRequestBase req,
                               int statusCode) throws IOException, ParseException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, statusCode, null, null, null);
        }
    }

    static void testForm(CloseableHttpClient hc, HttpPost req) throws IOException, ParseException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, 200, EntityUtils.toString(req.getEntity()), null, null);
        }
    }

    static void checkResult(CloseableHttpResponse res,
                            int statusCode,
                            @Nullable String body,
                            @Nullable Charset encoding,
                            @Nullable String contentType) throws IOException, ParseException {
        assertThat(res.getCode()).isEqualTo(statusCode);
        if (body != null) {
            if (encoding != null) {
                assertThat(EntityUtils.toString(res.getEntity(), encoding))
                        .isEqualTo(body);
            } else {
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo(body);
            }
        }

        final org.apache.hc.core5.http.Header header = res.getFirstHeader(CONTENT_TYPE);
        if (contentType != null) {
            assertThat(MediaType.parse(header.getValue())).isEqualTo(MediaType.parse(contentType));
        }
    }

    static HttpUriRequestBase get(String path) {
        return request(HttpMethod.GET, path, null, null);
    }

    static HttpUriRequestBase get(String path, @Nullable String accept) {
        return request(HttpMethod.GET, path, null, accept);
    }

    static HttpUriRequestBase post(String path) {
        return request(HttpMethod.POST, path, null, null);
    }

    static HttpUriRequestBase post(String path, @Nullable String contentType) {
        return request(HttpMethod.POST, path, contentType, null);
    }

    static HttpUriRequestBase post(String path, @Nullable String contentType, @Nullable String accept) {
        return request(HttpMethod.POST, path, contentType, accept);
    }

    static HttpPost form(String path) {
        return form(path, null, "armeria", "armeria");
    }

    static HttpPost form(String path, @Nullable Charset charset, String... kv) {
        final HttpPost req = (HttpPost) request(HttpMethod.POST, path, MediaType.FORM_DATA.toString());

        final List<NameValuePair> params = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.add(new BasicNameValuePair(kv[i], kv[i + 1]));
        }
        // HTTP.DEF_CONTENT_CHARSET = ISO-8859-1
        final Charset encoding = charset == null ? Charsets.ISO_8859_1 : charset;
        final UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params, encoding);
        req.setEntity(entity);
        return req;
    }

    static HttpUriRequestBase request(HttpMethod method, String path, @Nullable String contentType) {
        return request(method, path, contentType, null);
    }

    static HttpUriRequestBase request(HttpMethod method, String path,
                                      @Nullable String contentType, @Nullable String accept) {
        final HttpUriRequestBase req;
        switch (method) {
            case GET:
                req = new HttpGet(server.httpUri().resolve(path));
                break;
            case POST:
                req = new HttpPost(server.httpUri().resolve(path));
                break;
            default:
                throw new Error("Unexpected method: " + method);
        }
        if (contentType != null) {
            req.setHeader(CONTENT_TYPE, contentType);
        }
        if (accept != null) {
            req.setHeader(ACCEPT, accept);
        }
        return req;
    }

    static void validateContext(RequestContext ctx) {
        if (ServiceRequestContext.current() != ctx) {
            throw new RuntimeException("ServiceRequestContext instances are not same!");
        }
    }

    static void validateContextAndRequest(RequestContext ctx, Request req) {
        validateContext(ctx);
        if (ServiceRequestContext.current().request() != req) {
            throw new RuntimeException("HttpRequest instances are not same!");
        }
    }
}
