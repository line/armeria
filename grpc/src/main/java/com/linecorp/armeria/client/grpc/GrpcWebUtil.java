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

package com.linecorp.armeria.client.grpc;

import static java.util.Objects.requireNonNull;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.client.grpc.InternalGrpcWebUtil;

import io.grpc.ClientInterceptor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Utilities for working with <a href="https://grpc.io/docs/languages/web/basics/">gRPC-Web</a>.
 *
 * <p>Note that this class will be removed once a retry {@link ClientInterceptor} is added.
 * See: https://github.com/line/armeria/issues/2860
 */
@UnstableApi
public final class GrpcWebUtil {

    private static final int HEADER_LENGTH = 5;
    private static final int RESERVED_MASK = 0x7E;

    /**
     * Returns a {@link CompletableFuture} that will be completed with the gRPC-Web trailers
     * parsed from the content of the specified {@link HttpResponse}.
     * The future will be completed with {@code null} if fails to parse a gRPC-Web trailers.
     *
     * <p>A gRPC-Web response does not contain a separated trailers according to the
     * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md#protocol-differences-vs-grpc-over-http2">
     * gRPC-Web spec</a>:
     * <ul>
     *   <li>Trailers must be the last message of the response.</li>
     *   <li>Trailers may be sent together with response headers, with no message in the body.</li>
     * </ul>
     * That means the response trailers should be pulled out from {@link AggregatedHttpResponse#headers()}}
     * or parsed from {@link AggregatedHttpResponse#content()}.
     *
     * <p>This method is useful when {@link RetryRuleWithContent} needs {@link GrpcHeaderNames#GRPC_STATUS}
     * to decide whether to retry. For example:
     * <pre>{@code
     * Clients.builder(grpcServerUri)
     *        .decorator(RetryingClient.newDecorator(
     *                RetryRuleWithContent.onResponse((ctx, response) -> {
     *                    final CompletableFuture<HttpHeaders> future =
     *                            GrpcWebUtil.aggregateAndParseTrailers(ctx, res);
     *                    return future.thenApply(trailers -> trailers != null && trailers.getInt(
     *                            GrpcHeaderNames.GRPC_STATUS, -1) != 0);
     *                })))
     *        .build(MyGrpcStub.class);
     * }</pre>
     */
    public static CompletableFuture<HttpHeaders> aggregateAndParseTrailers(
            ClientRequestContext ctx, HttpResponse response) {
        requireNonNull(ctx, "ctx");
        requireNonNull(response, "response");
        final boolean grpcWebText = GrpcSerializationFormats.isGrpcWebText(
                ctx.log().partial().serializationFormat());
        final CompletableFuture<AggregatedHttpResponse> aggregated;
        if (grpcWebText) {
            aggregated = aggregateWithDecodingBase64(response);
        } else {
            aggregated = response.aggregate();
        }
        return aggregated.thenApply(aggregatedRes -> {
            final ByteBuf buf = aggregatedRes.content().byteBuf();
            while (buf.isReadable(HEADER_LENGTH)) {
                final short type = buf.readUnsignedByte();
                if ((type & RESERVED_MASK) != 0) {
                    // Malformed header
                    break;
                }

                final int length = buf.readInt();
                // 8th (MSB) bit of the 1st gRPC frame byte is:
                // - '1' for trailers
                // - '0' for data
                //
                // See: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md
                if (type >> 7 == 1) {
                    if ((type & 1) > 0) {
                        // TODO(minwoox) support compressed trailer.
                        break;
                    }
                    return InternalGrpcWebUtil.parseGrpcWebTrailers(buf);
                } else {
                    // Skip a gRPC content
                    buf.skipBytes(length);
                }
            }
            return null;
        });
    }

    private static CompletableFuture<AggregatedHttpResponse> aggregateWithDecodingBase64(
            HttpResponse response) {
        final FilteredHttpResponse filtered = new FilteredHttpResponse(response) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                if (obj instanceof HttpData) {
                    final HttpData data = (HttpData) obj;
                    final ByteBuf buf = data.byteBuf();
                    final ByteBuf decoded = Unpooled.wrappedBuffer(
                            Base64.getDecoder().decode(buf.nioBuffer()));
                    buf.release();
                    return HttpData.wrap(decoded);
                }
                return obj;
            }
        };
        return filtered.aggregate();
    }

    private GrpcWebUtil() {}
}
