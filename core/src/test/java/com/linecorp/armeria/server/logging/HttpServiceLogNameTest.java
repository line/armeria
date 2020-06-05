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

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpServiceLogNameTest {

    private static ServiceRequestContext capturedCtx;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/no-default", new MyHttpService());
            sb.service("/no-default/:id", new MyHttpService());
            sb.route()
              .get("/users/:id")
              .defaultServiceName("userService")
              .defaultLogName("profile")
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK));

            sb.annotatedService("/annotated", new MyAnnotatedService());
            sb.decorator((delegate, ctx, req) -> {
               capturedCtx = ctx;
               return delegate.serve(ctx, req);
            });
        }
    };

    WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void httpServiceWithoutDefault() {
        client.get("/no-default?a=1").aggregate().join();
        assertName("exact:/no-default", "GET");

        client.get("/no-default/10").aggregate().join();
        assertName("/no-default/:id", "GET");
    }

    @Test
    void httpServiceWithDefault() {
        client.get("/users/1").aggregate().join();
        assertName("userService", "profile");
    }

    @Test
    void annotatedService() {
        client.get("/annotated/hello").aggregate().join();
        assertName(MyAnnotatedService.class.getName(), "world");
    }

    private static void assertName(String serviceName, String name) {
        final RequestOnlyLog log = capturedCtx.log().whenRequestComplete().join();
        assertThat(log.serviceName()).isEqualTo(serviceName);
        assertThat(log.name()).isEqualTo(name);
        assertThat(log.fullName()).isEqualTo(serviceName + '/' + name);
    }

    private static class MyHttpService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    private static class MyAnnotatedService {
        @Get("/hello")
        public HttpResponse world() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
