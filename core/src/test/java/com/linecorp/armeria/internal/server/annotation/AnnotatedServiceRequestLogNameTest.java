/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class AnnotatedServiceRequestLogNameTest {

    private static final BlockingQueue<RequestLogAccess> logs = new LinkedTransferQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/ok")
                public String foo() {
                    return "OK";
                }

                @Get("/fail_early")
                public String bar() {
                    return "Not OK";
                }
            });

            sb.decorator((delegate, ctx, req) -> {
                logs.add(ctx.log());
                return delegate.serve(ctx, req);
            });

            // The annotated service will not be invoked at all for '/fail_early'.
            sb.routeDecorator().path("/fail_early")
              .build((delegate, ctx, req) -> {
                  throw HttpStatusException.of(500);
              });
        }
    };

    @Test
    void logNameShouldBeSet() throws Exception {
        WebClient.of(server.httpUri()).get("/ok").aggregate().join();

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.name()).isEqualTo("foo");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logNameShouldBeSetOnEarlyFailure() throws Exception {
        WebClient.of(server.httpUri()).get("/fail_early").aggregate().join();

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.name()).isEqualTo("bar");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
