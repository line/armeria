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

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.CompletableHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.common.grpc.protocol.StatusMessageEscaper;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.client.grpc.protocol.InternalGrpcWebUtil;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
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
    private static final Logger logger = LoggerFactory.getLogger(UnaryGrpcClient.class);

    /**
     * Constructs a {@link UnaryGrpcClient} for the given {@link WebClient}.
     *
     * @deprecated Prefer using a standard client building pattern, e.g.:
     *             <pre>{@code
     *             UnaryGrpcClient client =
     *                 Clients.newClient("gproto+http://127.0.0.1:8080", UnaryGrpcClient.class);
     *             }</pre>
     */
    @Deprecated
    public UnaryGrpcClient(WebClient webClient) {
        this(webClient, UnaryGrpcSerializationFormats.PROTO);
    }

    /**
     * Constructs a {@link UnaryGrpcClient} for the given {@link WebClient} and {@link SerializationFormat}.
     * The specified {@link SerializationFormat} should be one of {@code UnaryGrpcSerializationFormats#PROTO},
     * {@code UnaryGrpcSerializationFormats#PROTO_WEB}, or {@code UnaryGrpcSerializationFormats#PROTO_WEB_TEXT}.
     *
     * @deprecated Prefer using a standard client building pattern, e.g.:
     *             <pre>{@code
     *             UnaryGrpcClient client =
     *                 Clients.newClient("gproto-web+http://127.0.0.1:8080", UnaryGrpcClient.class);
     *             }</pre>
     */
    @Deprecated
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
        return webClient.execute(request).aggregateWithPooledObjects(PooledByteBufAllocator.DEFAULT)
                        .thenApply(msg -> {
                            try (HttpData content = msg.content()) {
                                if (msg.status() != HttpStatus.OK) {
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
                                return content.array();
                            }
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

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) {
            final CompletableHttpResponse response = HttpResponse.defer(ctx.eventLoop());
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
               .thenAccept(msg -> {
                   if (msg.status() != HttpStatus.OK || msg.content().isEmpty()) {
                       // Status can either be in the headers or trailers depending on error.
                       if (msg.headers().get(GrpcHeaderNames.GRPC_STATUS) != null) {
                           GrpcWebTrailers.set(ctx, msg.headers());
                       } else {
                           GrpcWebTrailers.set(ctx, msg.trailers());
                       }
                       // Nothing to deframe.
                       response.complete(msg.toHttpResponse());
                       return;
                   }

                   final ArmeriaMessageDeframer deframer =
                           new ArmeriaMessageDeframer(Integer.MAX_VALUE);
                   msg.toHttpResponse()
                      .decode(deframer, ctx.alloc(), byteBufConverter(ctx.alloc(), isGrpcWebText))
                      .subscribe(new DeframedMessageSubscriber(
                                         ctx, msg, serializationFormat, response),
                                 ctx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
               });
            return response;
        }
    }

    private static final class DeframedMessageSubscriber implements Subscriber<DeframedMessage> {
        private final ClientRequestContext ctx;
        private final AggregatedHttpResponse response;
        private final SerializationFormat serializationFormat;
        private final CompletableHttpResponse responseFuture;
        private final boolean isGrpcWeb;

        private HttpData content = HttpData.empty();
        @Nullable
        private HttpHeaders trailers;
        @Nullable
        private Subscription subscription;
        private boolean completed;
        private int processedMessages;

        private DeframedMessageSubscriber(ClientRequestContext ctx,
                                          AggregatedHttpResponse response,
                                          SerializationFormat serializationFormat,
                                          CompletableHttpResponse responseFuture) {
            this.ctx = ctx;
            this.response = response;
            this.serializationFormat = serializationFormat;
            this.responseFuture = responseFuture;
            isGrpcWeb = UnaryGrpcSerializationFormats.isGrpcWeb(serializationFormat);
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (subscription != null) {
                logger.error("onSubscribe was called multiple times");
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
                if (completed) {
                    return;
                }
                process(message);
            } finally {
                // It's a final consumer of the message and is responsible for closing it.
                message.close();
            }
        }

        @Override
        public void onError(Throwable t) {
            if (completed) {
                return;
            }
            completed = true;
            completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            if (completed) {
                return;
            }
            completed = true;
            if (trailers == null) {
                trailers = response.trailers();
            }
            GrpcWebTrailers.set(ctx, trailers);
            responseFuture.complete(HttpResponse.of(response.headers(), content, trailers));
        }

        private void process(DeframedMessage message) {
            final ByteBuf buf = message.buf();
            if (buf == null) {
                cancel(new ArmeriaStatusException(StatusCodes.INTERNAL,
                                                  "received compressed message; " +
                                                  "UnaryGrpcClient does not support compression."));
                return;
            }
            if (isGrpcWeb && message.isTrailer()) {
                trailers = InternalGrpcWebUtil.parseGrpcWebTrailers(buf);
                if (trailers == null) {
                    // Malformed trailers.
                    cancel(new ArmeriaStatusException(
                            StatusCodes.INTERNAL,
                            String.format("%s trailers malformed: %s",
                                          serializationFormat.uriText(),
                                          buf.toString(StandardCharsets.UTF_8))));
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
            // Retain the buffer after DeframedMessage is closed to use in the response.
            buf.retain();
            content = HttpData.wrap(buf);
            processedMessages++;
        }

        private void cancel(Throwable t) {
            if (completed) {
                return;
            }
            completed = true;
            if (subscription == null) {
                logger.error("subscriber has no active subscription");
            } else {
                subscription.cancel();
            }
            completeExceptionally(t);
        }

        private void completeExceptionally(Throwable t) {
            content.close();
            responseFuture.completeExceptionally(t);
        }
    }
}
