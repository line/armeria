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
package com.linecorp.armeria.internal.client.grpc;

import java.io.IOException;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.internal.client.grpc.protocol.InternalGrpcWebUtil;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.grpc.ForwardingDecompressor;

import io.grpc.ClientInterceptor;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Utilities for working with <a href="https://grpc.io/docs/languages/web/basics/">gRPC-Web</a>.
 *
 * <p>Note that this class will be removed once a retry {@link ClientInterceptor} is added.
 * See: https://github.com/line/armeria/issues/2860
 */
public final class GrpcWebTrailersExtractor implements DecoratingHttpClientFunction {

    private final int maxMessageSizeBytes;
    private final boolean grpcWebText;

    GrpcWebTrailersExtractor(int maxMessageSizeBytes, boolean grpcWebText) {
        this.maxMessageSizeBytes = maxMessageSizeBytes;
        this.grpcWebText = grpcWebText;
    }

    @Override
    public HttpResponse execute(HttpClient delegate, ClientRequestContext ctx, HttpRequest req)
            throws Exception {
        final HttpResponse response = delegate.execute(ctx, req);
        final ByteBufAllocator alloc = ctx.alloc();

        final ArmeriaMessageDeframer deframer =
                new ArmeriaMessageDeframer(maxMessageSizeBytes, alloc, grpcWebText);
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessage<DeframedMessage> deframed = publisher.decode(deframer, alloc);
        deframed.subscribe(new TrailersSubscriber(ctx), ctx.eventLoop());

        final FilteredHttpResponse filteredHttpResponse = new FilteredHttpResponse(response, true) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                if (obj instanceof ResponseHeaders) {
                    final ResponseHeaders headers = (ResponseHeaders) obj;
                    final String statusText = headers.get(HttpHeaderNames.STATUS);
                    if (statusText == null) {
                        // Missing status header.
                        publisher.close();
                        return obj;
                    }

                    if (ArmeriaHttpUtil.isInformational(statusText)) {
                        // Skip informational headers.
                        return obj;
                    }

                    final HttpStatus status = HttpStatus.valueOf(statusText);
                    if (!status.equals(HttpStatus.OK)) {
                        // Not OK status.
                        publisher.close();
                        return obj;
                    }

                    final String grpcEncoding = headers.get(GrpcHeaderNames.GRPC_ENCODING);
                    if (grpcEncoding != null) {
                        // We use DecompressorRegistry in ArmeriaClientCall. If ArmeriaClientCall
                        // supports to add another decompressor, we will change this to support that too.
                        final Decompressor decompressor =
                                DecompressorRegistry.getDefaultInstance().lookupDecompressor(grpcEncoding);
                        if (decompressor == null) {
                            // Can't find decompressor.
                            publisher.close();
                            return obj;
                        }
                        deframer.decompressor(ForwardingDecompressor.forGrpc(decompressor));
                    }
                    return obj;
                }

                if (obj instanceof HttpData && !publisher.isComplete()) {
                    final HttpData httpData = (HttpData) obj;
                    final HttpData wrapped = HttpData.wrap(
                            httpData.byteBuf(ByteBufAccessMode.RETAINED_DUPLICATE));
                    final boolean ignored = publisher.tryWrite(wrapped);
                }
                return obj;
            }
        };
        filteredHttpResponse.whenComplete().handle((unused, unused2) -> {
            publisher.close();
            return null;
        });
        return filteredHttpResponse;
    }

    private static final class TrailersSubscriber implements Subscriber<DeframedMessage> {

        private final ClientRequestContext ctx;

        TrailersSubscriber(ClientRequestContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onSubscribe(Subscription s) {
            // Backpressure is controlled by HttpDeframer.
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(DeframedMessage message) {
            if (message.isTrailer()) {
                final ByteBuf buf;
                try {
                    buf = InternalGrpcWebUtil.messageBuf(message, ctx.alloc());
                } catch (IOException e) {
                    // Ignore silently
                    return;
                }
                try {
                    final HttpHeaders trailers = InternalGrpcWebUtil.parseGrpcWebTrailers(buf);
                    if (trailers == null) {
                        return;
                    }
                    GrpcWebTrailers.set(ctx, trailers);
                } finally {
                    buf.release();
                }
            } else {
                message.close();
            }
        }

        @Override
        public void onError(Throwable t) {
            /* no-op */
        }

        @Override
        public void onComplete() {
            /* no-op */
        }
    }
}
