/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.rxjava;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesJsonSequences;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subscribers.DefaultSubscriber;

public class ObservableResponseConverterFunctionTest {

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
    }

    @Test
    public void completable() {
        final WebClient client = WebClient.of(rule.httpUri() + "/completable");

        AggregatedHttpResponse res;

        res = client.get("/done").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        res = client.get("/error").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
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
}
