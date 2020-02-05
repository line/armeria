/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpStatusTest {

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
            sb.service("/redirect", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.TEMPORARY_REDIRECT);
                }
            });
            sb.service("/client", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                }
            });
            sb.service("/server", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                }
            });
        }
    };

    static WebClient client;

    @BeforeAll
    static void setUp() {
        server.start();
        client = WebClient.of(server.httpUri("/"));
    }

    @AfterAll
    static void tearDown() {
        server.stop();
    }

    @Test
    void statusIsSuccessIfStatusCode2XX() {
        final AggregatedHttpResponse join = client.get("/success").aggregate().join();
        assertTrue(join.status().isSuccess());
    }

    @Test
    void statusIsRedirectionIfStatusCode3XX() {
        final AggregatedHttpResponse join = client.get("/redirect").aggregate().join();
        assertTrue(join.status().isRedirection());
    }

    @Test
    void statusIsErrorIfStatusCode4XX() {
        final AggregatedHttpResponse join = client.get("/client").aggregate().join();
        assertTrue(join.status().isError());
    }

    @Test
    void statusIsErrorIfStatusCode5XX() {
        final AggregatedHttpResponse join = client.get("/server").aggregate().join();
        assertTrue(join.status().isError());
    }
}
