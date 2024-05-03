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

package com.linecorp.armeria.internal.common.grpc;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.Status;

public final class HttpStreamDeframer extends ArmeriaMessageDeframer {

    private final RequestContext ctx;
    private final DecompressorRegistry decompressorRegistry;
    private final TransportStatusListener transportStatusListener;
    private final GrpcExceptionHandlerFunction exceptionHandler;

    @Nullable
    private StreamMessage<DeframedMessage> deframedStreamMessage;
    private boolean server;
    private boolean trailersReceived;

    public HttpStreamDeframer(
            DecompressorRegistry decompressorRegistry,
            RequestContext ctx,
            TransportStatusListener transportStatusListener,
            GrpcExceptionHandlerFunction exceptionHandler,
            int maxMessageLength, boolean grpcWebText, boolean server) {
        super(maxMessageLength, ctx.alloc(), grpcWebText);
        this.ctx = requireNonNull(ctx, "ctx");
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.transportStatusListener = requireNonNull(transportStatusListener, "transportStatusListener");
        this.exceptionHandler = exceptionHandler;
        this.server = server;
    }

    /**
     * Sets the deframed {@link StreamMessage}.
     * Note that the {@code deframedStreamMessage} should be set before processing the first {@link HttpObject}.
     */
    public void setDeframedStreamMessage(StreamMessage<DeframedMessage> deframedStreamMessage) {
        requireNonNull(deframedStreamMessage, "deframedStreamMessage");
        checkState(this.deframedStreamMessage == null, "deframedStreamMessage is already set");
        this.deframedStreamMessage = deframedStreamMessage;
    }

    @Override
    public void processHeaders(HttpHeaders headers, StreamDecoderOutput<DeframedMessage> out) {
        if (headers instanceof RequestHeaders) {
            // RequestHeaders is handled by (Un)FramedGrpcService.
            return;
        }

        final String statusText = headers.get(HttpHeaderNames.STATUS);
        if (statusText == null) {
            // Not allowed to have empty leading headers, kill the stream hard.
            transportStatusListener.transportReportStatus(
                    Status.INTERNAL.withDescription("Missing HTTP status code"));
            return;
        }

        final HttpStatus status = HttpStatus.valueOf(statusText);
        if (!status.equals(HttpStatus.OK)) {
            // Just mark trailers as received since a non-OK response may not have trailers.
            trailersReceived = true;
            transportStatusListener.transportReportStatus(
                    GrpcStatus.httpStatusToGrpcStatus(status.code()));
            return;
        }

        final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
        if (grpcStatus != null) {
            assert deframedStreamMessage != null;
            trailersReceived = true;
            GrpcStatus.reportStatusLater(headers, deframedStreamMessage, transportStatusListener);
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
                final Metadata metadata = new Metadata();
                transportStatusListener.transportReportStatus(exceptionHandler.apply(ctx, t, metadata),
                                                              metadata);
                return;
            }
        }

        if (grpcStatus == null) {
            // exclude trailers-only responses from triggering the callback
            final Metadata metadata = MetadataUtil.copyFromHeaders(headers);
            // Note: this implementation slightly differs from upstream in that
            // we don't check if the content-type is valid before invoking this callback.
            transportStatusListener.transportReportHeaders(metadata);
        }
    }

    @Override
    public void processTrailers(HttpHeaders headers, StreamDecoderOutput<DeframedMessage> out) {
        final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
        if (grpcStatus != null) {
            assert deframedStreamMessage != null;
            trailersReceived = true;
            GrpcStatus.reportStatusLater(headers, deframedStreamMessage, transportStatusListener);
        }
    }

    @Override
    public void processOnError(Throwable cause) {
        final Metadata metadata = new Metadata();
        transportStatusListener.transportReportStatus(exceptionHandler.apply(ctx, cause, metadata), metadata);
    }

    @Override
    public void processOnComplete(StreamDecoderInput in, StreamDecoderOutput<DeframedMessage> out)
            throws Exception {
        if (!server && !trailersReceived) {
            // A gRPC response should contain grpc-status in trailers if HTTP status is OK.
            final Status status = Status.INTERNAL.withDescription("Missing gRPC status code");
            transportStatusListener.transportReportStatus(status);
            // Raise an exception to clean up `in` and `out`.
            throw status.asRuntimeException();
        }
    }

    @Override
    public HttpStreamDeframer decompressor(@Nullable Decompressor decompressor) {
        return (HttpStreamDeframer) super.decompressor(decompressor);
    }
}
