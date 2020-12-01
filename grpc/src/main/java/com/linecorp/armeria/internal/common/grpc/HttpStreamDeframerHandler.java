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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframerHandler;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.HttpDeframerOutput;

import io.grpc.DecompressorRegistry;
import io.grpc.Status;

public final class HttpStreamDeframerHandler extends ArmeriaMessageDeframerHandler {

    private final DecompressorRegistry decompressorRegistry;
    private final TransportStatusListener transportStatusListener;
    @Nullable
    private final GrpcStatusFunction statusFunction;

    @Nullable
    private HttpDeframer<DeframedMessage> deframer;

    public HttpStreamDeframerHandler(
            DecompressorRegistry decompressorRegistry,
            TransportStatusListener transportStatusListener,
            @Nullable GrpcStatusFunction statusFunction,
            int maxMessageSizeBytes) {
        super(maxMessageSizeBytes);
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.transportStatusListener = requireNonNull(transportStatusListener, "transportStatusListener");
        this.statusFunction = statusFunction;
    }

    /**
     * Sets the specified {@link HttpDeframer}.
     * Note that the deframer should be set before processing the first {@link HttpObject}.
     */
    public void setDeframer(HttpDeframer<DeframedMessage> deframer) {
        requireNonNull(deframer, "deframer");
        checkState(this.deframer == null, "deframer is already set");
        this.deframer = deframer;
    }

    @Override
    public void processHeaders(HttpHeaders headers, HttpDeframerOutput<DeframedMessage> out) {
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
            assert deframer != null;
            GrpcStatus.reportStatusLater(headers, deframer, transportStatusListener);
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
                transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(statusFunction, t));
            }
        }
    }

    @Override
    public void processTrailers(HttpHeaders headers, HttpDeframerOutput<DeframedMessage> out) {
        final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
        if (grpcStatus != null) {
            assert deframer != null;
            GrpcStatus.reportStatusLater(headers, deframer, transportStatusListener);
        }
    }

    @Override
    public void processOnError(Throwable cause) {
        transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(statusFunction, cause));
    }

    @Override
    public HttpStreamDeframerHandler decompressor(@Nullable Decompressor decompressor) {
        return (HttpStreamDeframerHandler) super.decompressor(decompressor);
    }
}
