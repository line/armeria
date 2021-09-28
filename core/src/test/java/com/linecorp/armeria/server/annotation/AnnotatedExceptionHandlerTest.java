/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedExceptionHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedServiceExtensions(ImmutableList.of(), ImmutableList.of(),
                                          ImmutableList.of((ctx, req, cause) -> {
                                              if (cause instanceof AnticipatedException) {
                                                  return HttpResponse.of("Handled");
                                              } else {
                                                  return ExceptionHandlerFunction.fallthrough();
                                              }
                                          }));
            sb.annotatedService(new Object() {
                @Get("/recover")
                @Decorator(AlwaysThrowDecorator.class)
                public String recover() {
                    return "world hello!";
                }
            });
        }
    };

    @Test
    void shouldHandleExceptionRaisedInDecorator() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/recover").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("Handled");
    }

    static class AlwaysThrowDecorator implements DecoratingHttpServiceFunction {

        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            throw new AnticipatedException();
        }
    }
}
