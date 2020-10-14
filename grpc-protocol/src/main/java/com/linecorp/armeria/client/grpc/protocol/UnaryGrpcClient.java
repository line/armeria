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

package com.linecorp.armeria.client.grpc.protocol;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.StatusMessageEscaper;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderValues;

/**
 * A {@link UnaryGrpcClient} can be used to make requests to a gRPC server without depending on gRPC stubs.
 * This client takes care of deframing and framing with the gRPC wire format and handling appropriate headers.
 *
 * <p>This client does not support compression. If you need support for compression, please consider using
 * normal gRPC stubs or file a feature request.
 */
@UnstableApi
public final class UnaryGrpcClient {

    private final WebClient webClient;

    /**
     * Constructs a {@link UnaryGrpcClient} for the given {@link WebClient}.
     */
    // TODO(anuraaga): We would ideally use our standard client building pattern, i.e.,
    // Clients.builder(...).build(UnaryGrpcClient.class), but that requires mapping protocol schemes to media
    // types, which cannot be duplicated. As this and normal gproto+ clients must use the same media type, we
    // cannot currently implement this without rethinking / refactoring core and punt for now since this is an
    // advanced API.
    public UnaryGrpcClient(WebClient webClient) {
        this.webClient = Clients.newDerivedClient(
                webClient,
                ClientOptions.DECORATION.newValue(
                        ClientDecoration.of(GrpcFramingDecorator::new)
                ));
    }

    /**
     * Executes a unary gRPC client request. The given {@code payload} will be framed and sent to the path at
     * {@code uri}. {@code uri} should be the method's URI, which is always of the format
     * {@code /:package-name.:service-name/:method}. For example, for the proto package
     * {@code armeria.protocol}, the service name {@code CoolService} and the method name
     * {@code RunWithoutStubs}, the {@code uri} would be {@code /armeria.protocol.CoolService/RunWithoutStubs}.
     * If you aren't sure what the package, service name, and method name are for your method, you should
     * probably use normal gRPC stubs instead of this class.
     */
    public CompletableFuture<byte[]> execute(String uri, byte[] payload) {
        final HttpRequest request = HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, uri,
                                  HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto",
                                  HttpHeaderNames.TE, HttpHeaderValues.TRAILERS),
                HttpData.wrap(payload));
        return webClient.execute(request).aggregate()
                        .thenApply(msg -> {
                            if (!HttpStatus.OK.equals(msg.status())) {
                                throw new ArmeriaStatusException(
                                        StatusCodes.INTERNAL,
                                        "Non-successful HTTP response code: " + msg.status());
                            }

                            // Status can either be in the headers or trailers depending on error
                            String grpcStatus = msg.headers().get(GrpcHeaderNames.GRPC_STATUS);
                            if (grpcStatus != null) {
                                checkGrpcStatus(grpcStatus, msg.headers());
                            } else {
                                grpcStatus = msg.trailers().get(GrpcHeaderNames.GRPC_STATUS);
                                checkGrpcStatus(grpcStatus, msg.trailers());
                            }

                            return msg.content().array();
                        });
    }

    private static void checkGrpcStatus(@Nullable String grpcStatus, HttpHeaders headers) {
        if (grpcStatus != null && !"0".equals(grpcStatus)) {
            String grpcMessage = headers.get(GrpcHeaderNames.GRPC_MESSAGE);
            if (grpcMessage != null) {
                grpcMessage = StatusMessageEscaper.unescape(grpcMessage);
            }
            throw new ArmeriaStatusException(Integer.parseInt(grpcStatus), grpcMessage);
        }
    }

    private static final class GrpcFramingDecorator extends SimpleDecoratingHttpClient {

        private GrpcFramingDecorator(HttpClient delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(
                    req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc())
                       .thenCompose(
                               msg -> {
                                   final ByteBuf buf = msg.content().byteBuf();
                                   final HttpData framed;
                                   try (ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                                           ctx.alloc(), Integer.MAX_VALUE, false)) {
                                       framed = framer.writePayload(buf);
                                   }

                                   try {
                                       return unwrap().execute(ctx, HttpRequest.of(req.headers(), framed))
                                                      .aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc());
                                   } catch (Exception e) {
                                       throw new ArmeriaStatusException(StatusCodes.INTERNAL,
                                                                        "Error executing request.");
                                   }
                               })
                       .thenCompose(msg -> {
                           if (!msg.status().equals(HttpStatus.OK) || msg.content().isEmpty()) {
                               // Nothing to deframe.
                               return CompletableFuture.completedFuture(msg.toHttpResponse());
                           }

                           final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();

                           final ArmeriaMessageDeframerHandler handler =
                                   new ArmeriaMessageDeframerHandler(Integer.MAX_VALUE);
                           final HttpDeframer<DeframedMessage> deframer =
                                   new HttpDeframer<>(handler, ctx.alloc());

                           StreamMessage.of(msg.content()).subscribe(deframer, ctx.eventLoop());
                           deframer.subscribe(singleSubscriber(msg, responseFuture), ctx.eventLoop());
                           return responseFuture;
                       }), ctx.eventLoop());
        }

        private static Subscriber<DeframedMessage> singleSubscriber(
                AggregatedHttpResponse msg, CompletableFuture<HttpResponse> responseFuture) {

            return new Subscriber<DeframedMessage>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(1);
                }

                @Override
                public void onNext(DeframedMessage unframed) {
                    final ByteBuf buf = unframed.buf();
                    // Compression not supported.
                    assert buf != null;
                    responseFuture.complete(HttpResponse.of(msg.headers(), HttpData.wrap(buf).withEndOfStream(),
                                                            msg.trailers()));
                }

                @Override
                public void onError(Throwable t) {
                    responseFuture.completeExceptionally(t);
                }

                @Override
                public void onComplete() {
                    if (!responseFuture.isDone()) {
                        responseFuture.complete(
                                HttpResponse.of(msg.headers(), HttpData.empty(), msg.trailers()));
                    }
                }
            };
        }
    }
}
