/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.rxjava2;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesJsonSequences;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subscribers.DefaultSubscriber;

public class ObservableResponseConverterFunctionTest {

    private static class CheckCtxConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            validateContext(ctx);
            return ResponseConverterFunction.fallthrough();
        }
    }

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/maybe", new Object() {
                @Get("/string")
                public Maybe<String> string() {
                    return Maybe.just("a");
                }

                @Get("/json")
                @ProducesJson
                public Maybe<String> json() {
                    return Maybe.just("a");
                }

                @Get("/empty")
                public Maybe<String> empty() {
                    return Maybe.empty();
                }

                @Get("/error")
                public Maybe<String> error() {
                    return Maybe.error(new AnticipatedException());
                }

                @Get("/http-response")
                public Maybe<HttpResponse> httpResponse() {
                    return Maybe.just(HttpResponse.of("a"));
                }

                @Post("/defer-empty-post")
                public Maybe<String> deferEmptyPost() {
                    final RequestContext ctx = RequestContext.current();
                    return Maybe.defer(
                            () -> {
                                validateContext(ctx);
                                return Maybe.just("a");
                            });
                }

                @Post("/defer-post")
                public Maybe<String> deferPost(String request) {
                    final RequestContext ctx = RequestContext.current();
                    return Maybe.defer(
                            () -> {
                                validateContext(ctx);
                                return Maybe.just(request);
                            });
                }
            });

            sb.annotatedService("/single", new Object() {
                @Get("/string")
                public Single<String> string() {
                    return Single.just("a");
                }

                @Get("/json")
                @ProducesJson
                public Single<String> json() {
                    return Single.just("a");
                }

                @Get("/error")
                public Single<String> error() {
                    return Single.error(new AnticipatedException());
                }

                @Get("/http-response")
                public Single<HttpResponse> httpResponse() {
                    return Single.just(HttpResponse.of("a"));
                }

                @Post("/defer-empty-post")
                public Single<String> deferEmptyPost() {
                    final RequestContext ctx = RequestContext.current();
                    return Single.defer(
                            () -> {
                                validateContext(ctx);
                                return Single.just("a");
                            });
                }

                @Post("/defer-post")
                public Single<String> deferPost(String request) {
                    final RequestContext ctx = RequestContext.current();
                    return Single.defer(
                            () -> {
                                validateContext(ctx);
                                return Single.just(request);
                            });
                }

                @Post("/defer-post2")
                @ResponseConverter(CheckCtxConverter.class)
                public Single<String> deferPostValidateCtx(String request) {
                    final RequestContext ctx = RequestContext.current();
                    return Single.defer(
                            () -> {
                                validateContext(ctx);
                                return Single.just(request);
                            });
                }

                @Post("/defer-post-switch-thread")
                @ResponseConverter(CheckCtxConverter.class)
                public Single<String> deferPostSwitchThreadValidateCtx(String request) {
                    final RequestContext ctx = RequestContext.current();
                    return Single.defer(() -> {
                        validateContext(ctx);
                        return Single.just(request);
                    }).flatMap(value -> Single.create(
                            emitter -> new Thread(() -> emitter.onSuccess(value)).start()
                    ));
                }
            });

            sb.annotatedService("/completable", new Object() {
                @Get("/done")
                public Completable done() {
                    return Completable.complete();
                }

                @Get("/error")
                public Completable error() {
                    return Completable.error(new AnticipatedException());
                }

                @Post("/defer-empty-post")
                public Completable deferEmptyPost() {
                    final RequestContext ctx = RequestContext.current();
                    return Completable.defer(
                            () -> {
                                validateContext(ctx);
                                return Completable.complete();
                            });
                }

                @Post("/defer-post")
                public Completable deferPost(String request) {
                    final RequestContext ctx = RequestContext.current();
                    return Completable.defer(
                            () -> {
                                validateContext(ctx);
                                return Completable.complete();
                            });
                }
            });

            sb.annotatedService("/flowable", new Object() {
                @Get("/string")
                @ProducesText
                public Flowable<String> string() {
                    return Flowable.just("a");
                }

                @Get("/json/1")
                @ProducesJson
                public Flowable<String> json1() {
                    return Flowable.just("a");
                }

                @Get("/json/3")
                @ProducesJson
                public Flowable<String> json3() {
                    return Flowable.just("a", "b", "c");
                }

                @Get("/error")
                public Flowable<String> error() {
                    return Flowable.error(new AnticipatedException());
                }

                @Post("/defer-post")
                @ProducesJson
                public Flowable<String> deferPost(String request) {
                    final RequestContext ctx = RequestContext.current();
                    return Flowable.defer(
                            () -> {
                                validateContext(ctx);
                                return Flowable.just("a", "b", "c");
                            });
                }
            });

            sb.annotatedService("/observable", new Object() {
                @Get("/string")
                @ProducesText
                public Observable<String> string() {
                    return Observable.just("a");
                }

                @Get("/json/1")
                @ProducesJson
                public Observable<String> json1() {
                    return Observable.just("a");
                }

                @Get("/json/3")
                @ProducesJson
                public Observable<String> json3() {
                    return Observable.just("a", "b", "c");
                }

                @Get("/error")
                public Observable<String> error() {
                    return Observable.error(new AnticipatedException());
                }

                @Post("/defer-post")
                @ProducesJson
                public Observable<String> deferPost(String request) {
                    final RequestContext ctx = RequestContext.current();
                    return Observable.defer(
                            () -> {
                                validateContext(ctx);
                                return Observable.just("a", "b", "c");
                            });
                }
            });

            sb.annotatedService("/streaming", new Object() {
                @Get("/json")
                @ProducesJsonSequences
                public Observable<String> json() {
                    return Observable.just("a", "b", "c");
                }
            });

            sb.annotatedService("/failure", new Object() {
                @Get("/immediate1")
                public Observable<String> immediate1() {
                    throw new IllegalArgumentException("Bad request!");
                }

                @Get("/immediate2")
                public Observable<String> immediate2() {
                    return Observable.error(new IllegalArgumentException("Bad request!"));
                }

                @Get("/defer1")
                public Observable<String> defer1() {
                    return Observable.defer(
                            () -> Observable.error(new IllegalArgumentException("Bad request!")));
                }

                @Get("/defer2")
                public Observable<String> defer2() {
                    return Observable.switchOnNext(Observable.just(
                            Observable.just("a", "b", "c"),
                            Observable.error(new IllegalArgumentException("Bad request!"))));
                }
            });
        }
    };

    @Test
    public void maybe() {
        final WebClient client = WebClient.of(rule.httpUri() + "/maybe");

        AggregatedHttpResponse res;

        res = client.get("/string").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.get("/json").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isStringEqualTo("a");

        res = client.get("/empty").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.content().isEmpty()).isTrue();

        res = client.get("/error").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        res = client.get("/http-response").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.post("/defer-empty-post", "").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.post("/defer-post", "b").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("b");
    }

    @Test
    public void single() {
        final WebClient client = WebClient.of(rule.httpUri() + "/single");

        AggregatedHttpResponse res;

        res = client.get("/string").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.get("/json").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isStringEqualTo("a");

        res = client.get("/error").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        res = client.get("/http-response").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.post("/defer-empty-post", "").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.post("/defer-post", "b").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("b");

        res = client.post("/defer-post2", "b").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("b");

        res = client.post("/defer-post-switch-thread", "b").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("b");
    }

    @Test
    public void completable() {
        final WebClient client = WebClient.of(rule.httpUri() + "/completable");

        AggregatedHttpResponse res;

        res = client.get("/done").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        res = client.get("/error").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        res = client.post("/defer-empty-post", "").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        res = client.post("/defer-post", "b").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    public void observable() {
        final WebClient client = WebClient.of(rule.httpUri() + "/observable");

        AggregatedHttpResponse res;

        res = client.get("/string").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.get("/json/1").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isArray().ofLength(1).thatContains("a");

        res = client.get("/json/3").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isArray().ofLength(3).thatContains("a").thatContains("b").thatContains("c");

        res = client.get("/error").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        res = client.post("/defer-post", "").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isArray().ofLength(3).thatContains("a").thatContains("b").thatContains("c");
    }

    @Test
    public void flowable() {
        final WebClient client = WebClient.of(rule.httpUri() + "/flowable");

        AggregatedHttpResponse res;

        res = client.get("/string").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("a");

        res = client.get("/json/1").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isArray().ofLength(1).thatContains("a");

        res = client.get("/json/3").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isArray().ofLength(3).thatContains("a").thatContains("b").thatContains("c");

        res = client.get("/error").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        res = client.post("/defer-post", "").aggregate().join();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isArray().ofLength(3).thatContains("a").thatContains("b").thatContains("c");
    }

    @Test
    public void streaming() {
        final WebClient client = WebClient.of(rule.httpUri() + "/streaming");
        final AtomicBoolean isFinished = new AtomicBoolean();
        client.get("/json").subscribe(new DefaultSubscriber<HttpObject>() {
            final ImmutableList.Builder<HttpObject> received = new Builder<>();

            @Override
            public void onNext(HttpObject httpObject) {
                received.add(httpObject);
            }

            @Override
            public void onError(Throwable t) {
                throw new Error("Should not reach here.");
            }

            @Override
            public void onComplete() {
                final Iterator<HttpObject> it = received.build().iterator();
                final ResponseHeaders headers = (ResponseHeaders) it.next();
                assertThat(headers.status()).isEqualTo(HttpStatus.OK);
                assertThat(headers.contentType()).isEqualTo(MediaType.JSON_SEQ);
                // JSON Text Sequences: *(Record Separator[0x1E] JSON-text Line Feed[0x0A])
                assertThat(((HttpData) it.next()).array())
                        .isEqualTo(new byte[] { 0x1E, '\"', 'a', '\"', 0x0A });
                assertThat(((HttpData) it.next()).array())
                        .isEqualTo(new byte[] { 0x1E, '\"', 'b', '\"', 0x0A });
                assertThat(((HttpData) it.next()).array())
                        .isEqualTo(new byte[] { 0x1E, '\"', 'c', '\"', 0x0A });
                assertThat(((HttpData) it.next()).isEmpty()).isTrue();
                assertThat(it.hasNext()).isFalse();
                isFinished.set(true);
            }
        });
        await().until(isFinished::get);
    }

    @Test
    public void failure() {
        final WebClient client = WebClient.of(rule.httpUri() + "/failure");

        AggregatedHttpResponse res;

        res = client.get("/immediate1").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = client.get("/immediate2").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = client.get("/defer1").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = client.get("/defer2").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static void validateContext(RequestContext ctx) {
        if (ServiceRequestContext.current() != ctx) {
            throw new RuntimeException("ServiceRequestContext instances are not same!");
        }
    }
}
