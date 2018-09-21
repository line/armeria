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

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.testing.server.ServerRule;

import io.reactivex.Observable;

public class ObservableResponseConverterFunctionTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/success", new Object() {
                @Get("/1")
                public Observable<String> one() {
                    return Observable.just("a");
                }

                @Get("/3")
                @ProducesJson
                public Observable<String> three() {
                    return Observable.just("a", "b", "c");
                }
            });

            sb.annotatedService("/failure", new Object() {
                @Get("/immediate")
                public Observable<String> immediate() {
                    throw new IllegalArgumentException("Bad request!");
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
    public void success() {
        final HttpClient client = HttpClient.of(rule.uri("/success"));

        AggregatedHttpMessage msg;

        msg = client.get("/1").aggregate().join();
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(msg.content().toStringUtf8()).isEqualTo("a");

        msg = client.get("/3").aggregate().join();
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(msg.content().toStringUtf8())
                .isArray().ofLength(3)
                .thatContains("a").thatContains("b").thatContains("c");
    }

    @Test
    public void failure() {
        final HttpClient client = HttpClient.of(rule.uri("/failure"));

        AggregatedHttpMessage msg;

        msg = client.get("/immediate").aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        msg = client.get("/defer1").aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        msg = client.get("/defer2").aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
