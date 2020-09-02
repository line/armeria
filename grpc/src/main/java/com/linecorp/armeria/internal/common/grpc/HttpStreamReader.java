/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.HttpDeframerOutput;

import io.grpc.DecompressorRegistry;
import io.grpc.Status;
import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link Processor} to read HTTP messages and pass to gRPC business logic.
 */
public final class HttpStreamReader extends ArmeriaMessageDeframer {

    private final DecompressorRegistry decompressorRegistry;
    private final TransportStatusListener transportStatusListener;

    public HttpStreamReader(DecompressorRegistry decompressorRegistry,
                            TransportStatusListener transportStatusListener,
                            ByteBufAllocator alloc, int maxMessageSizeBytes, boolean decodeBase64) {
        super(alloc, maxMessageSizeBytes, decodeBase64);
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.transportStatusListener = requireNonNull(transportStatusListener, "transportStatusListener");
    }

    @Override
    protected void processHeaders(HttpHeaders headers, HttpDeframerOutput<DeframedMessage> out) {
        // Only clients will see headers from a stream. It doesn't hurt to share this logic between server
        // and client though as everything else is identical.
        final String statusText = headers.get(HttpHeaderNames.STATUS);
        if (statusText == null) {
            // Not allowed to have empty leading headers, kill the stream hard.
            transportStatusListener.transportReportStatus(
                    Status.INTERNAL.withDescription("Missing HTTP status code"));
            return;
        }

        final HttpStatus status = HttpStatus.valueOf(statusText);
        if (!status.equals(HttpStatus.OK)) {
            transportStatusListener.transportReportStatus(
                    GrpcStatus.httpStatusToGrpcStatus(status.code()));
            return;
        }

        final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
        if (grpcStatus != null) {
            // A gRPC client could not receive messages fully yet.
            // Let ArmeriaClientCall be closed when the gRPC client has been consumed all messages.
            whenComplete().thenRun(() -> {
                GrpcStatus.reportStatus(headers, this, transportStatusListener);
            });
        }

        // Headers without grpc-status are the leading headers of a non-failing response, prepare to receive
        // messages.
        final String grpcEncoding = headers.get(GrpcHeaderNames.GRPC_ENCODING);
        if (grpcEncoding != null) {
            final io.grpc.Decompressor decompressor = decompressorRegistry.lookupDecompressor(grpcEncoding);
            if (decompressor == null) {
                transportStatusListener.transportReportStatus(Status.INTERNAL.withDescription(
                        "Can't find decompressor for " + grpcEncoding));
                return;
            }
            try {
                decompressor(ForwardingDecompressor.forGrpc(decompressor));
            } catch (Throwable t) {
                transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(t));
            }
        }
    }

    @Override
    protected void processTrailers(HttpHeaders headers, HttpDeframerOutput<DeframedMessage> out) {
        final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
        if (grpcStatus != null) {
            whenConsumed().thenRun(() -> GrpcStatus.reportStatus(headers, this, transportStatusListener));
        }
    }

    @Override
    protected void processOnError(Throwable cause) {
        transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(cause));
    }

    /**
     * Cancel this stream and prevents further subscription.
     */
    public void cancel() {
       abort();
    }

    @Override
    public HttpStreamReader decompressor(@Nullable Decompressor decompressor) {
        return (HttpStreamReader) super.decompressor(decompressor);
    }
}
