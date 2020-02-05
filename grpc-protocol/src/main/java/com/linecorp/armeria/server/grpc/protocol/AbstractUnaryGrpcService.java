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

package com.linecorp.armeria.server.grpc.protocol;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.util.UnstableApi;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * An {@link AbstractUnaryGrpcService} can be used to implement a gRPC service without depending on gRPC stubs.
 * This service takes care of deframing and framing with the gRPC wire format and handling appropriate headers.
 *
 * <p>This service does not support compression. If you need support for compression, please consider using
 * normal gRPC stubs or file a feature request.
 */
@UnstableApi
public abstract class AbstractUnaryGrpcService extends AbstractUnsafeUnaryGrpcService {

    /**
     * Returns an unframed response message to return to the client, given an unframed request message. It is
     * expected that the implementation has the logic to know how to parse the request and serialize a response
     * into {@code byte[]}. The returned {@code byte[]} will be framed and returned to the client.
     */
    protected abstract CompletableFuture<byte[]> handleMessage(byte[] message);

    @Override
    protected final CompletableFuture<ByteBuf> handleMessage(ByteBuf message) {
        final byte[] bytes;
        try {
            bytes = ByteBufUtil.getBytes(message);
        } finally {
            message.release();
        }
        return handleMessage(bytes).thenApply(Unpooled::wrappedBuffer);
    }
}
