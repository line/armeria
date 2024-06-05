/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AggregatingRequestTimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(100)
              .service("/timeout/sync", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(200);
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              })
              .service("/timeout/async", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(req.aggregate().thenApply(agg -> {
                          return HttpResponse.of(200);
                      }));
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              });
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C", "HTTP" })
    void synchronouslyHandleFailedRequest(SessionProtocol protocol) {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/timeout/sync"),
                                                   StreamMessage.streaming()); // Do not send body.
        // The service synchronously responds without aggregating the request body.
        assertThat(WebClient.of(protocol, server.endpoint(protocol))
                            .execute(request).aggregate().join().status())
                .isSameAs(HttpStatus.OK);
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C", "HTTP" })
    void asynchronouslyHandleFailedRequest(SessionProtocol protocol) {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/timeout/async"),
                                                   StreamMessage.streaming()); // Do not send body.
        // The service waits for the full request body and then responds.
        assertThat(WebClient.of(protocol, server.endpoint(protocol))
                            .execute(request).aggregate().join().status())
                .isSameAs(HttpStatus.REQUEST_TIMEOUT);
    }
}
