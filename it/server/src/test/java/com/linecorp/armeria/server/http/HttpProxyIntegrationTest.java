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

package com.linecorp.armeria.server.http;

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

/** Test for issues that may happen when doing simple proxying. */
public class HttpProxyIntegrationTest {

    @ClassRule
    public static ServerRule backendServer = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", ((ctx, req) -> {
                HttpResponseWriter writer = HttpResponse.streaming();

                HttpHeaders headers = HttpHeaders.of(HttpStatus.OK);

                HttpHeaders trailers = new DefaultHttpHeaders()
                        .set(HttpHeaderNames.of("armeria-message"),
                             "error");

                writer.write(headers);
                writer.write(trailers);
                writer.close();

                return writer;
            }));

            sb.decorator(LoggingService.newDecorator());
        }
    };

    @ClassRule
    public static ServerRule frontendServer = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> {
                HttpClient client = HttpClient.of(backendServer.uri("/"));
                return client.get("/");
            });

            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    public void proxyWithTrailers() throws Exception {
        HttpClient client = HttpClient.of(frontendServer.uri("/"));

        client.get("/").subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject obj) {
                System.out.println(obj);
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("error");
            }

            @Override
            public void onComplete() {
                System.out.println("complete");
            }
        });
        Thread.sleep(Long.MAX_VALUE);
    }
}
