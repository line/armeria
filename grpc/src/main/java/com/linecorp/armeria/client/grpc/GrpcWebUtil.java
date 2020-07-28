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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.client.grpc.InternalGrpcWebUtil;

import io.grpc.ClientInterceptor;
import io.netty.buffer.ByteBuf;

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
     * Returns a gRPC-Web trailers parsed from the specified response body.
     * {@code null} if fail to parse a gRPC-Web trailers.
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
     *                    return response.aggregate().thenApply(aggregated -> {
     *                        HttpHeaders trailers = GrpcWebUtil.parseTrailers(ctx, aggregated.content());
     *                        // Retry if the 'grpc-status' is not equal to 0.
     *                        return trailers != null && trailers.getInt(GrpcHeaderNames.GRPC_STATUS) != 0;
     *                    });
     *                })))
     *        .build(MyGrpcStub.class);
     * }</pre>
     */
    @Nullable
    public static HttpHeaders parseTrailers(ClientRequestContext ctx, HttpData response) {
        requireNonNull(ctx, "ctx");
        requireNonNull(response, "response");
        final SerializationFormat serializationFormat =
                ctx.log().ensureAvailable(RequestLogProperty.SCHEME).scheme().serializationFormat();
        if (GrpcSerializationFormats.isGrpcWebText(serializationFormat)) {
            // TODO(minwoox) support decoding base64.
            return null;
        }
        final ByteBuf buf = response.byteBuf();

        HttpHeaders trailers = null;
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
                trailers = InternalGrpcWebUtil.parseGrpcWebTrailers(buf);
                break;
            } else {
                // Skip a gRPC content
                buf.skipBytes(length);
            }
        }
        return trailers;
    }

    private GrpcWebUtil() {}
}
