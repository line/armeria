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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.unsafe.PooledHttpData;
import com.linecorp.armeria.internal.client.grpc.InternalGrpcWebUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Utilities for working with <a href="https://grpc.io/docs/languages/web/basics/">gRPC-Web</a>.
 */
public final class GrpcWebUtil {

    private static final int HEADER_LENGTH = 5;
    private static final int RESERVED_MASK = 0x7E;

    /**
     * Returns a gRPC-Web trailers parsed from the specified response body.
     * {@code null} if fail to parse a gRPC-Web trailers.
     *
     * <p>A gRPC-Web response does not contains separated trailers according to the
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
     * to decide whether to retry.
     * For example:
     * <pre>{@code
     * Clients.builder(grpcServerUri)
     *        .decorator(RetryingClient.newDecorator(
     *                RetryRuleWithContent.onResponse(response -> {
     *                    return response.aggregate().thenApply(aggregated -> {
     *                        HttpHeaders trailers = GrpcWebUtil.parseTrailers(aggregated.content());
     *                        // Retry if the 'grpc-status' is not equal to 0.
     *                        return trailers != null && trailers.getInt(GrpcHeaderNames.GRPC_STATUS) != 0;
     *                    });
     *                })))
     *        .build(MyGrpcStub.class);
     * }</pre>
     */
    @Nullable
    static HttpHeaders parseTrailers(HttpData response) {
        final ByteBuf buf;
        if (response instanceof PooledHttpData) {
            buf = ((PooledHttpData) response).content();
        } else {
            buf = Unpooled.wrappedBuffer(response.array());
        }
        final int readerIndex = buf.readerIndex();

        try {
            HttpHeaders trailers = null;
            while (buf.isReadable(HEADER_LENGTH)) {
                final short type = buf.readUnsignedByte();
                if ((type & RESERVED_MASK) != 0) {
                    // Malformed header
                    break;
                }

                final int length = buf.readInt();
                if (type >> 7 == 1) {
                    trailers = InternalGrpcWebUtil.parseGrpcWebTrailers(buf);
                    break;
                } else {
                    buf.skipBytes(length);
                }
            }
            return trailers;
        } finally {
            buf.readerIndex(readerIndex);
        }
    }

    private GrpcWebUtil() {}
}
