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

import static com.linecorp.armeria.internal.common.grpc.protocol.Base64DecoderUtil.byteBufConverter;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.CANCELLATION_OPTION;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.StatusMessageEscaper;
import com.linecorp.armeria.internal.client.grpc.protocol.InternalGrpcWebUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;

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
    private static final Set<SerializationFormat> SUPPORTED_SERIALIZATION_FORMATS =
            UnaryGrpcSerializationFormats.values();

    private final SerializationFormat serializationFormat;
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
        this(webClient, UnaryGrpcSerializationFormats.PROTO);
    }

    /**
     * Constructs a {@link UnaryGrpcClient} for the given {@link WebClient} and {@link SerializationFormat}.
     * The specified {@link SerializationFormat} should be one of {@code UnaryGrpcSerializationFormats#PROTO},
     * {@code UnaryGrpcSerializationFormats#PROTO_WEB}, or {@code UnaryGrpcSerializationFormats#PROTO_WEB_TEXT}.
     */
    public UnaryGrpcClient(WebClient webClient, SerializationFormat serializationFormat) {
        if (!SUPPORTED_SERIALIZATION_FORMATS.contains(serializationFormat)) {
            throw new IllegalArgumentException("serializationFormat: " + serializationFormat +
                                               " (expected: one of " + SUPPORTED_SERIALIZATION_FORMATS + ')');
        }
        this.serializationFormat = serializationFormat;
        this.webClient = Clients.newDerivedClient(
                webClient,
                ClientOptions.DECORATION.newValue(ClientDecoration.of(
                        delegate -> new GrpcFramingDecorator(delegate, serializationFormat))));
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
                RequestHeaders.builder(HttpMethod.POST, uri).contentType(serializationFormat.mediaType())
                              .add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS.toString()).build(),
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
        private final SerializationFormat serializationFormat;
        private final boolean isGrpcWebText;

        private GrpcFramingDecorator(HttpClient delegate, SerializationFormat serializationFormat) {
            super(delegate);
            // Validated in the UnaryGrpcClient ctor.
            this.serializationFormat = serializationFormat;
            isGrpcWebText = UnaryGrpcSerializationFormats.isGrpcWebText(serializationFormat);
        }

        private static Subscriber<DeframedMessage> singleSubscriber(
                ClientRequestContext ctx, AggregatedHttpResponse msg,
                SerializationFormat serializationFormat,
                CompletableFuture<HttpResponse> responseFuture) {

            return new Subscriber<DeframedMessage>() {
                private HttpData content = HttpData.empty();
                @Nullable
                private HttpHeaders trailers;
                @Nullable
                private Subscription subscription;
                @Nullable
                private Throwable cause;
                private int processedMessages;

                @Override
                public void onSubscribe(Subscription s) {
                    if (subscription != null) {
                        s.cancel();
                        return;
                    }
                    subscription = s;
                    // At least 2 requests are required for receiving trailers.
                    s.request(2);
                }

                @Override
                public void onNext(DeframedMessage message) {
                    try {
                        process(message);
                    } finally {
                        message.close();
                    }
                }

                private void process(DeframedMessage message) {
                    if (UnaryGrpcSerializationFormats.isGrpcWeb(serializationFormat) && message.isTrailer()) {
                        final ByteBuf buf;
                        try {
                            buf = InternalGrpcWebUtil.messageBuf(message, ctx.alloc());
                        } catch (IOException t) {
                            cancel(t);
                            return;
                        }
                        if (buf == message.buf()) {
                            // Buffer will be finally cleaned up by the DeframedMessage.close.
                            buf.retain();
                        }
                        try {
                            trailers = InternalGrpcWebUtil.parseGrpcWebTrailers(buf);
                            if (trailers == null) {
                                // Malformed trailers.
                                cancel(new ArmeriaStatusException(
                                        StatusCodes.INTERNAL,
                                        serializationFormat.uriText() + " trailers malformed: " + buf
                                                .toString(StandardCharsets.UTF_8)));
                            }
                        } finally {
                            buf.release();
                        }
                        processedMessages++;
                        return;
                    }
                    if (processedMessages > 0) {
                        cancel(new ArmeriaStatusException(StatusCodes.INTERNAL,
                                                          "received more than one data message; " +
                                                          "UnaryGrpcClient does not support streaming."));
                        return;
                    }
                    final ByteBuf buf = message.buf();
                    // Compression not supported.
                    assert buf != null;
                    content = HttpData.wrap(buf);
                    buf.retain();
                    processedMessages++;
                }

                @Override
                public void onError(Throwable t) {
                    setCause(t);
                    content.close();
                    responseFuture.completeExceptionally(cause);
                }

                @Override
                public void onComplete() {
                    if (trailers == null) {
                        trailers = msg.trailers();
                    }
                    responseFuture.complete(HttpResponse.of(msg.headers(), content, trailers));
                }

                private void setCause(Throwable t) {
                    if (cause == null) {
                        cause = t;
                    }
                }

                private void cancel(Throwable t) {
                    setCause(t);
                    if (subscription != null) {
                        subscription.cancel();
                    }
                }
            };
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(
                    req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc())
                       .thenCompose(
                               msg -> {
                                   try (HttpData content = msg.content()) {
                                       final ByteBuf buf = content.byteBuf();
                                       final HttpData framed;
                                       try (ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                                               ctx.alloc(), Integer.MAX_VALUE, isGrpcWebText)) {
                                           framed = framer.writePayload(buf);
                                       }

                                       try {
                                           return unwrap().execute(ctx, HttpRequest.of(req.headers(), framed))
                                                          .aggregateWithPooledObjects(ctx.eventLoop(),
                                                                                      ctx.alloc());
                                       } catch (Exception e) {
                                           throw new ArmeriaStatusException(StatusCodes.INTERNAL,
                                                                            "Error executing request.");
                                       }
                                   }
                               })
                       .thenCompose(msg -> {
                           try (HttpData content = msg.content()) {
                               if (!msg.status().equals(HttpStatus.OK) || content.isEmpty()) {
                                   // Nothing to deframe.
                                   return CompletableFuture.completedFuture(msg.toHttpResponse());
                               }

                               final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                               final ArmeriaMessageDeframer deframer =
                                       new ArmeriaMessageDeframer(Integer.MAX_VALUE);
                               msg.toHttpResponse().decode(deframer, ctx.alloc(),
                                                           byteBufConverter(ctx.alloc(), isGrpcWebText))
                                  .subscribe(singleSubscriber(ctx, msg, serializationFormat, responseFuture),
                                             ctx.eventLoop(), CANCELLATION_OPTION);
                               return responseFuture;
                           }
                       }), ctx.eventLoop());
        }
    }
}
