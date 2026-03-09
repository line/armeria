/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.DelegatingHttpJsonTranscodingService;
import com.linecorp.armeria.server.grpc.DelegatingHttpJsonTranscodingServiceBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class DelegatingHttpJsonTranscodingServiceTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
    private static final AtomicReference<CompletableFuture<Void>> unconsumedGrpcRequestCompletion =
            new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension upstreamServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder("/json",
                            GrpcService.builder()
                                       .addService(new HttpJsonTranscodingTestService())
                                       .supportedSerializationFormats(GrpcSerializationFormats.JSON)
                                       .build());
            sb.serviceUnder("/proto",
                            GrpcService.builder()
                                       .addService(new HttpJsonTranscodingTestService())
                                       .supportedSerializationFormats(GrpcSerializationFormats.PROTO)
                                       .build());
        }
    };

    @RegisterExtension
    static final ServerExtension proxyServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route().pathPrefix("/").build((ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));
        }
    };

    private static void reconfigureProxyServer(Consumer<ServerBuilder> configurator) {
        proxyServer.server().reconfigure(sb -> {
            sb.decorator(proxyServer.requestContextCaptor().newDecorator());
            configurator.accept(sb);
        });
    }

    private static HttpService prefixedProxy(WebClient client, String prefix) {
        return (ctx, req) -> {
            final HttpRequest newReq = req.mapHeaders(
                    headers -> headers.toBuilder().path(prefix + headers.path()).build());
            ctx.updateRequest(newReq);
            return client.execute(newReq);
        };
    }

    private static WebClient upstreamClient() {
        return WebClient.of(upstreamServer.uri(SessionProtocol.H2C));
    }

    private static DelegatingHttpJsonTranscodingServiceBuilder transcoderBuilder(HttpService delegate) {
        return DelegatingHttpJsonTranscodingService.builder(delegate)
                                                   .serviceDescriptors(
                                                           HttpJsonTranscodingTestServiceGrpc
                                                                   .getServiceDescriptor());
    }

    @Test
    void shouldExposeHttpEndpointSupportViaAs() {
        final DelegatingHttpJsonTranscodingService transcoder =
                transcoderBuilder((ctx, req) -> HttpResponse.of(HttpStatus.OK)).build();
        final HttpEndpointSupport support = transcoder.as(HttpEndpointSupport.class);
        assertThat(support).isNotNull();
        assertThat(transcoder.routes()).isNotEmpty();
        final Route route = transcoder.routes().iterator().next();
        assertThat(support.httpEndpointSpecification(route)).isNotNull();
    }

    @Test
    void shouldReturnSelfForAssignableTypes() {
        final DelegatingHttpJsonTranscodingService transcoder =
                transcoderBuilder((ctx, req) -> HttpResponse.of(HttpStatus.OK)).build();
        assertThat(transcoder.as(DelegatingHttpJsonTranscodingService.class)).isSameAs(transcoder);
        assertThat(transcoder.as(HttpServiceWithRoutes.class)).isSameAs(transcoder);
    }

    @Test
    void shouldProxyHttpJsonRequest() throws Exception {
        reconfigureProxyServer(sb -> {
            final HttpService jsonDelegate = prefixedProxy(upstreamClient(), "/json");
            sb.service(transcoderBuilder(jsonDelegate).build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestWithPrefix() throws Exception {
        reconfigureProxyServer(sb -> {
            final HttpService jsonDelegate = prefixedProxy(upstreamClient(), "/json");
            sb.serviceUnder("/proxy", transcoderBuilder(jsonDelegate).build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/proxy/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestWithProtoUpstream() throws Exception {
        reconfigureProxyServer(sb -> {
            final HttpService protoDelegate = prefixedProxy(upstreamClient(), "/proto");
            sb.serviceUnder("/proto",
                            transcoderBuilder(protoDelegate)
                                    .transcodedGrpcSerializationFormat(GrpcSerializationFormats.PROTO)
                                    .build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/proto/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestInProcess() throws Exception {
        reconfigureProxyServer(sb -> {
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(new HttpJsonTranscodingTestService())
                                                       .build();
            sb.serviceUnder("/inproc", transcoderBuilder(grpcService).build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/inproc/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestInProcessWithGrpcServicePath() throws Exception {
        reconfigureProxyServer(sb -> {
            final GrpcService grpcServiceWithPath = GrpcService.builder()
                                                               .addService("/custom",
                                                                           new HttpJsonTranscodingTestService())
                                                               .build();
            sb.serviceUnder("/inproc-path", transcoderBuilder(grpcServiceWithPath).build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/inproc-path/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldReturnUnimplementedForMismatchedService() throws Exception {
        reconfigureProxyServer(sb -> {
            final GrpcService mismatchedGrpcService = GrpcService.builder()
                                                                 .addService(new MismatchedTestService())
                                                                 .build();
            sb.serviceUnder("/mismatch", transcoderBuilder(mismatchedGrpcService).build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/mismatch/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("grpc-code").asText()).isEqualTo("UNIMPLEMENTED");
    }

    @Test
    void shouldFailWhenDelegateReturnsNonGrpcResponse() {
        reconfigureProxyServer(sb -> {
            final DelegatingHttpJsonTranscodingService badDelegateTranscoder =
                    transcoderBuilder((ctx, req) -> HttpResponse.of(
                            req.aggregate(ctx.eventLoop())
                               .thenApply(unused -> HttpResponse.of(HttpStatus.NOT_FOUND))))
                            .build();
            sb.serviceUnder("/bad-delegate", badDelegateTranscoder);
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/bad-delegate/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void shouldFailWhenNestedTranscoderIsUsedAsDelegate() {
        reconfigureProxyServer(sb -> {
            final HttpService jsonDelegate = prefixedProxy(upstreamClient(), "/json");
            final DelegatingHttpJsonTranscodingService nestedDelegate =
                    transcoderBuilder(jsonDelegate).build();
            final DelegatingHttpJsonTranscodingService nestedTranscoder =
                    transcoderBuilder(nestedDelegate).build();
            sb.serviceUnder("/nested", nestedTranscoder);
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/nested/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void shouldNotDelegateWhenRouteIsNotTranscoding() {
        reconfigureProxyServer(sb -> {
            final DelegatingHttpJsonTranscodingService catchAllTranscoder =
                    transcoderBuilder((ctx, req) -> HttpResponse.of(HttpStatus.OK)).build();
            sb.route().pathPrefix("/catch-all").build(catchAllTranscoder);
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/catch-all/not-a-transcoding-route").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldUseCustomFallbackWhenRouteIsNotTranscoding() {
        reconfigureProxyServer(sb -> {
            final DelegatingHttpJsonTranscodingService customFallbackTranscoder =
                    transcoderBuilder((ctx, req) -> HttpResponse.of(HttpStatus.OK))
                            .fallback((ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE))
                            .build();
            sb.route().pathPrefix("/custom-fallback").build(customFallbackTranscoder);
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/custom-fallback/not-a-transcoding-route").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldAbortUnconsumedGrpcRequest() {
        reconfigureProxyServer(sb -> {
            final DelegatingHttpJsonTranscodingService abortingTranscoder =
                    transcoderBuilder((ctx, req) -> {
                        unconsumedGrpcRequestCompletion.set(req.whenComplete());
                        final ResponseHeaders headers =
                                ResponseHeaders.builder(HttpStatus.OK)
                                               .contentType(GrpcSerializationFormats.JSON.mediaType())
                                               .add(GrpcHeaderNames.GRPC_STATUS, "0")
                                               .build();
                        return HttpResponse.of(headers);
                    }).build();
            sb.serviceUnder("/abort-request", abortingTranscoder);
        });
        proxyServer.requestContextCaptor().clear();
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/abort-request/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        await().untilAsserted(() -> {
            final CompletableFuture<Void> completion = unconsumedGrpcRequestCompletion.get();
            assertThat(completion).isNotNull();
            assertThat(completion).isDone();
            final Throwable cause = completion.handle((unused, ex) -> ex).join();
            assertThat(cause).isInstanceOf(ResponseCompleteException.class);
        });
    }

    @Test
    void shouldCompleteRequestLogAfterTranscoding() throws Exception {
        reconfigureProxyServer(sb -> {
            final HttpService jsonDelegate = prefixedProxy(upstreamClient(), "/json");
            sb.service(transcoderBuilder(jsonDelegate).build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        final ServiceRequestContext ctx = proxyServer.requestContextCaptor().take();
        await().untilAsserted(() -> assertThat(ctx.log().isComplete()).isTrue());
    }

    @Test
    void shouldProxyHttpJsonRequestInProcessWithTranscodingEnabled() throws Exception {
        reconfigureProxyServer(sb -> {
            final GrpcService grpcServiceWithTranscodingEnabled =
                    GrpcService.builder()
                               .addService(new HttpJsonTranscodingTestService())
                               .enableUnframedRequests(true)
                               .enableHttpJsonTranscoding(true)
                               .supportedSerializationFormats(GrpcSerializationFormats.JSON)
                               .build();
            sb.serviceUnder("/inproc-enabled",
                            transcoderBuilder(grpcServiceWithTranscodingEnabled).build());
        });
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/inproc-enabled/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    private static final class MismatchedTestService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request,
                              StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder().setUsername("mismatch").build());
            responseObserver.onCompleted();
        }
    }
}
