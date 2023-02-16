/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Publisher;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest.BinaryService;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest.ConverterProvider;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest.HttpFileService;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest.JsonService;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest.ServerSentEventService;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest.Streaming;
import com.linecorp.armeria.internal.server.annotation.ResponseStreamingTest.StringService;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJsonSequences;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class AnnotatedServiceResponseExchangeTypeTest {

    private static final AttributeKey<ExchangeType> EXCHANGE_TYPE =
            AttributeKey.valueOf(ResponseStreamingTest.class, "EXCHANGE_TYPE");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService("/JsonService", new JsonService())
              .annotatedService("/BinaryService", new BinaryService())
              .annotatedService("/ServerSentEventService", new ServerSentEventService())
              .annotatedService("/StringService", new StringService())
              .annotatedService("/HttpFileService", new HttpFileService())
              .annotatedService("/UnaryRequestService", new Object() {
                  @Post("/unary")
                  public String unary(AggregatedHttpRequest req) {
                      return "unary";
                  }

                  @ProducesJsonSequences
                  @Post("/responseStreaming")
                  public Publisher<String> responseStreaming(AggregatedHttpRequest req) {
                      return StreamMessage.of("responseStreaming");
                  }
              });

            sb.decorator((delegate, ctx, req) -> {
                final MediaType produceType = ctx.negotiatedResponseMediaType();
                final RoutingResultBuilder routingResultBuilder =
                        RoutingResult.builder()
                                     .path(ctx.path())
                                     .type(RoutingResultType.MATCHED);
                if (produceType != null) {
                    routingResultBuilder.negotiatedResponseMediaType(produceType);
                }
                final ExchangeType exchangeType =
                        ctx.config().service().exchangeType(ctx.routingContext());
                ctx.setAttr(EXCHANGE_TYPE, exchangeType);
                return delegate.serve(ctx, req);
            });
        }
    };

    @ArgumentsSource(ConverterProvider.class)
    @ParameterizedTest
    void responseStreaming_exchangeType(ResponseConverterFunction unused, Class<?> serviceClass)
            throws InterruptedException {
        for (Method method : serviceClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            final boolean isResponseStreaming = method.getAnnotation(Streaming.class).value();
            final ExchangeType expected;
            if (isResponseStreaming) {
                expected = ExchangeType.BIDI_STREAMING;
            } else {
                expected = ExchangeType.REQUEST_STREAMING;
            }

            final BlockingWebClient client = server.webClient().blocking();
            final String methodName = method.getName();
            final ResponseEntity<String> response =
                    client.prepare()
                          .get('/' + serviceClass.getSimpleName() + '/' + methodName)
                          .asString()
                          .execute();
            assertThat(response.content())
                    .as("%s should support %s", methodName, expected)
                    .contains(methodName);
            final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
            assertThat(log.context().attr(EXCHANGE_TYPE))
                    .as("%s should support %s", methodName, expected)
                    .isEqualTo(expected);
        }
    }

    @Test
    void unaryService() throws InterruptedException {
        final AggregatedHttpResponse response =
                server.webClient().blocking().post("/UnaryRequestService/unary", "aggregation");
        final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
        assertThat(log.context().attr(EXCHANGE_TYPE)).isEqualTo(ExchangeType.UNARY);
        assertThat(response.contentUtf8()).isEqualTo("unary");
    }

    @Test
    void responseStreamingService() throws InterruptedException {
        final AggregatedHttpResponse response =
                server.webClient().blocking()
                      .post("/UnaryRequestService/responseStreaming", "aggregation");
        final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
        assertThat(log.context().attr(EXCHANGE_TYPE)).isEqualTo(ExchangeType.RESPONSE_STREAMING);
        assertThat(response.contentUtf8()).contains("responseStreaming");
    }
}
