/*
 * Copyright 2022 LINE Corporation
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

import static com.linecorp.armeria.common.ExchangeType.BIDI_STREAMING;
import static com.linecorp.armeria.common.ExchangeType.UNARY;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderValues;

@SuppressWarnings({ "Convert2Lambda", "AnonymousInnerClassMayBeStatic" })
class HeadRequestTest {

    private static final HttpData RESPONSE_BODY = HttpData.ofUtf8("Hello!");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/fixed-static", new ExchangeTypeService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(RESPONSE_BODY.toStringUtf8());
                }
            });

            sb.service("/fixed-conditional", new ExchangeTypeService() {

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    if (req.headers().method() == HttpMethod.HEAD) {
                        return HttpResponse.builder()
                                           .ok()
                                           .header(HttpHeaderNames.CONTENT_LENGTH, RESPONSE_BODY.length())
                                           .build();
                    }
                    return HttpResponse.of(RESPONSE_BODY.toStringUtf8());
                }
            });

            sb.service("/streaming-static", new ExchangeTypeService() {

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    final HttpResponseWriter response = HttpResponse.streaming();
                    response.write(ResponseHeaders.of(200));
                    response.write(RESPONSE_BODY);
                    response.close();
                    return response;
                }
            });

            sb.service("/streaming-conditional", new ExchangeTypeService() {

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    if (req.headers().method() == HttpMethod.HEAD) {
                        final ResponseHeaders headers =
                                ResponseHeaders.builder()
                                               .status(200)
                                               // Indicate that content-length should not be filled
                                               // automatically.
                                               .contentLengthUnknown()
                                               .build();
                        return HttpResponse.of(headers);
                    } else {
                        final HttpResponseWriter response = HttpResponse.streaming();
                        response.write(ResponseHeaders.of(200));
                        response.write(RESPONSE_BODY);
                        response.close();
                        return response;
                    }
                }
            });

            sb.service("/streaming-conditional-length", new ExchangeTypeService() {

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    final ResponseHeaders headers = ResponseHeaders.builder(200)
                                                                   .contentLength(RESPONSE_BODY.length())
                                                                   .build();
                    if (req.headers().method() == HttpMethod.HEAD) {
                        return HttpResponse.of(headers);
                    } else {
                        final HttpResponseWriter response = HttpResponse.streaming();
                        response.write(headers);
                        response.write(RESPONSE_BODY);
                        response.close();
                        return response;
                    }
                }
            });

            sb.service("/empty-body", new ExchangeTypeService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(ResponseHeaders.of(200));
                }
            });

            sb.service("/no-content", new ExchangeTypeService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.NO_CONTENT);
                }
            });

            sb.decorator(LoggingService.newDecorator());
        }
    };

    @ArgumentsSource(NonTransferEncodingArgumentsProvider.class)
    @ParameterizedTest
    void shouldReturnContentLengthForNonTransferEncoding_javaClient(String path, Version version,
                                                                    ExchangeType exchangeType,
                                                                    HttpMethod method)
            throws Exception {

        // Use Java HttpClient to check legacy HTTP/1 headers which Armeria client disallows.
        final HttpClient javaClient = HttpClient.newHttpClient();
        final java.net.http.HttpRequest request = newJavaRequest(path, version, exchangeType, method);
        final java.net.http.HttpResponse<String> response = javaClient.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(response.headers().firstValueAsLong(HttpHeaderNames.CONTENT_LENGTH.toString()))
                .hasValue(RESPONSE_BODY.length());
        assertThat(response.headers().firstValue(HttpHeaderNames.TRANSFER_ENCODING.toString()))
                .isEmpty();

        if (method == HttpMethod.HEAD) {
            assertThat(response.body()).isEmpty();
        } else {
            assertThat(response.body()).isEqualTo(RESPONSE_BODY.toStringUtf8());
        }
    }

    @ArgumentsSource(NonTransferEncodingArgumentsProvider.class)
    @ParameterizedTest
    void shouldReturnContentLengthForNonTransferEncoding_armeriaClient(String path, Version version,
                                                                       ExchangeType exchangeType,
                                                                       HttpMethod method) {
        HttpRequest request = newArmeriaRequest(path, version, exchangeType, method);

        final SplitHttpResponse splitResponse = WebClient.of().execute(request).split();
        final ResponseHeaders splitHeaders = splitResponse.headers().join();
        final byte[] body = splitResponse.body().collectBytes().join();
        if (method == HttpMethod.HEAD) {
            assertThat(body).isEmpty();
        } else {
            assertThat(body).isEqualTo(RESPONSE_BODY.array());
        }

        // Make sure that AggregatedHttpResponse preserves the original content-length.
        request = newArmeriaRequest(path, version, exchangeType, method);
        final AggregatedHttpResponse aggregatedResponse = BlockingWebClient.of().execute(request);
        if (method == HttpMethod.HEAD) {
            assertThat(aggregatedResponse.contentUtf8()).isEmpty();
        } else {
            assertThat(aggregatedResponse.contentUtf8()).isEqualTo(RESPONSE_BODY.toStringUtf8());
        }

        for (ResponseHeaders headers : ImmutableList.of(splitHeaders, aggregatedResponse.headers())) {
            assertThat(headers.status()).isEqualTo(HttpStatus.OK);
            assertThat(headers.getInt(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(RESPONSE_BODY.length());
            assertThat(headers.contentLength()).isEqualTo(RESPONSE_BODY.length());
            assertThat(headers.isContentLengthUnknown()).isFalse();
        }
    }

    @ArgumentsSource(TransferEncodingArgumentsProvider.class)
    @ParameterizedTest
    void shouldNotReturnContentLengthForTransferEncoding_javaClient(String path, Version version,
                                                                    ExchangeType exchangeType,
                                                                    HttpMethod method)
            throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final java.net.http.HttpRequest request = newJavaRequest(path, version, exchangeType, method);
        final java.net.http.HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(response.headers().firstValueAsLong(HttpHeaderNames.CONTENT_LENGTH.toString()))
                .isEmpty();
        if (version == Version.HTTP_1_1 && method != HttpMethod.HEAD) {
            // A response to HEAD method does not have transfer-encoding header.
            assertThat(response.headers().firstValue(HttpHeaderNames.TRANSFER_ENCODING.toString()))
                    .hasValue(HttpHeaderValues.CHUNKED.toString());
        }

        if (method == HttpMethod.HEAD) {
            assertThat(response.body()).isEmpty();
        } else {
            assertThat(response.body()).isEqualTo(RESPONSE_BODY.toStringUtf8());
        }
    }

    @ArgumentsSource(TransferEncodingArgumentsProvider.class)
    @ParameterizedTest
    void shouldNotReturnContentLengthForTransferEncoding_armeriaClient(String path, Version version,
                                                                       ExchangeType exchangeType,
                                                                       HttpMethod method) {
        HttpRequest request = newArmeriaRequest(path, version, exchangeType, method);
        final SplitHttpResponse splitResponse = WebClient.of().execute(request).split();
        final ResponseHeaders splitHeaders = splitResponse.headers().join();

        final byte[] body = splitResponse.body().collectBytes().join();
        if (method == HttpMethod.HEAD) {
            assertThat(body).isEmpty();
        } else {
            assertThat(body).isEqualTo(RESPONSE_BODY.array());
        }

        request = newArmeriaRequest(path, version, exchangeType, method);
        final AggregatedHttpResponse aggregatedResponse = BlockingWebClient.of().execute(request);
        assertThat(aggregatedResponse.status()).isEqualTo(HttpStatus.OK);
        if (method == HttpMethod.HEAD) {
            assertThat(aggregatedResponse.contentUtf8()).isEmpty();
        } else {
            assertThat(aggregatedResponse.contentUtf8()).isEqualTo(RESPONSE_BODY.toStringUtf8());
        }

        for (ResponseHeaders headers : ImmutableList.of(splitHeaders, aggregatedResponse.headers())) {
            assertThat(headers.status()).isEqualTo(HttpStatus.OK);
            assertThat(headers.get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
            assertThat(headers.contentLength()).isEqualTo(-1);
            // `isContentLengthSet()` should be set always for decoded responses.
            assertThat(headers.isContentLengthUnknown()).isTrue();
        }
    }

    @ArgumentsSource(EmptyContentArgumentsProvider.class)
    @ParameterizedTest
    void shouldReturnZeroContentLength_javaClient(String path, Version version, ExchangeType exchangeType,
                                                  HttpMethod method) throws Exception {

        final HttpClient client = HttpClient.newHttpClient();
        final java.net.http.HttpRequest request = newJavaRequest(path, version, exchangeType, method);
        final java.net.http.HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(response.headers().firstValueAsLong(HttpHeaderNames.CONTENT_LENGTH.toString()))
                .hasValue(0);
        assertThat(response.body()).isEmpty();
    }

    @ArgumentsSource(EmptyContentArgumentsProvider.class)
    @ParameterizedTest
    void shouldReturnZeroContentLength_armeriaClient(String path, Version version, ExchangeType exchangeType,
                                                     HttpMethod method) throws Exception {

        HttpRequest request = newArmeriaRequest(path, version, exchangeType, method);
        final SplitHttpResponse splitResponse = WebClient.of().execute(request).split();
        final ResponseHeaders splitHeaders = splitResponse.headers().join();
        assertThat(splitHeaders.status()).isEqualTo(HttpStatus.OK);
        assertThat(splitHeaders.contentLength()).isZero();
        assertThat(splitResponse.body().collectBytes().join()).isEmpty();

        request = newArmeriaRequest(path, version, exchangeType, method);
        final AggregatedHttpResponse aggregatedResponse = BlockingWebClient.of().execute(request);
        assertThat(aggregatedResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedResponse.headers().contentLength()).isZero();
        assertThat(aggregatedResponse.content().isEmpty()).isTrue();
    }

    private static java.net.http.HttpRequest newJavaRequest(String path, Version version,
                                                            ExchangeType exchangeType, HttpMethod method) {
        final java.net.http.HttpRequest request =
                java.net.http.HttpRequest.newBuilder()
                                         .version(version)
                                         .uri(server.httpUri()
                                                    .resolve(path + "?exchangeType=" + exchangeType))
                                         .method(method.name(), BodyPublishers.noBody())
                                         .build();
        return request;
    }

    private static HttpRequest newArmeriaRequest(String path, Version version,
                                                 ExchangeType exchangeType, HttpMethod method) {
        final SessionProtocol protocol =
                version == Version.HTTP_1_1 ? SessionProtocol.H1C : SessionProtocol.H2C;

        return HttpRequest.builder()
                          .method(method)
                          .path(server.uri(protocol)
                                      .resolve(path + "?exchangeType=" + exchangeType).toString())
                          .build();
    }

    private static class NonTransferEncodingArgumentsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Streams.concat(
                    withDefaultArguments("/fixed-static", UNARY, BIDI_STREAMING),
                    withDefaultArguments("/fixed-conditional", UNARY, BIDI_STREAMING),
                    withDefaultArguments("/streaming-static", UNARY),
                    withDefaultArguments("/streaming-conditional-length", UNARY, BIDI_STREAMING));
        }
    }

    private static class TransferEncodingArgumentsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Streams.concat(
                    withDefaultArguments("/streaming-static", BIDI_STREAMING),
                    withDefaultArguments("/streaming-conditional", BIDI_STREAMING)
            );
        }
    }

    private static class EmptyContentArgumentsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return withDefaultArguments("/empty-body", UNARY, BIDI_STREAMING);
        }
    }

    private static Stream<? extends Arguments> withDefaultArguments(String path,
                                                                    ExchangeType... exchangeTypes) {
        return Arrays.stream(Version.values()).flatMap(version -> {
            return Arrays.stream(exchangeTypes).flatMap(exchangeType -> {
                return ImmutableList.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.HEAD).stream()
                                    .map(method -> Arguments.of(path, version, exchangeType, method));
            });
        });
    }

    @FunctionalInterface
    private interface ExchangeTypeService extends HttpService {

        @Override
        default ExchangeType exchangeType(RoutingContext routingContext) {
            return ExchangeType.valueOf(routingContext.params().get("exchangeType"));
        }
    }
}
