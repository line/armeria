/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.internal.grpc.ArmeriaWritableBuffer;
import com.linecorp.armeria.internal.grpc.ArmeriaWritableBufferAllocator;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.AbstractServerStream;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportFrameUtil;
import io.grpc.internal.WritableBuffer;
import io.netty.util.AsciiString;

/**
 * An {@link AbstractServerStream} that converts GRPC structures and writes to an Armeria
 * {@link HttpResponseWriter}. GRPC will have already taken care of compression, framing, etc.
 */
final class ArmeriaGrpcServerStream extends AbstractServerStream {

    private final ArmeriaMessageReader messageReader;
    private final HttpResponseWriter responseWriter;
    private final Sink sink;
    private final TransportState transportState;
    private final long maxMessageSize;

    ArmeriaGrpcServerStream(
            HttpResponseWriter responseWriter, long maxMessageSize, StatsTraceContext statsCtx) {
        super(new ArmeriaWritableBufferAllocator(), statsCtx);
        this.responseWriter = responseWriter;
        this.maxMessageSize = maxMessageSize;
        sink = new Sink();
        transportState = new TransportState(statsCtx);
        messageReader = new ArmeriaMessageReader(transportState);
    }

    ArmeriaMessageReader messageReader() {
        return messageReader;
    }

    @Override
    protected TransportState transportState() {
        return transportState;
    }

    @Override
    protected AbstractServerStream.Sink abstractServerStreamSink() {
        return sink;
    }

    private class Sink implements AbstractServerStream.Sink {

        @Override
        public void writeHeaders(Metadata metadata) {
            HttpHeaders armeriaHeaders = new DefaultHttpHeaders(true, metadata.headerCount());
            fillArmeriaHeaders(metadata, armeriaHeaders);
            responseWriter.write(armeriaHeaders);
        }

        // Armeria always flushes so we ignore the flush parameter.
        @Override
        public void writeFrame(@Nullable WritableBuffer frame, boolean flush) {
            if (frame == null) {
                // GRPC uses a null frame to indicate a request to flush the stream.
                // Armeria flushes every message so no need to do anything here.
                return;
            }

            final ArmeriaWritableBuffer f = (ArmeriaWritableBuffer) frame;
            final HttpData data = HttpData.of(f.array(), 0, f.readableBytes());
            responseWriter.write(data);
        }

        @Override
        public void writeTrailers(Metadata trailers, boolean headersSent) {
            if (!headersSent) {
                HttpHeaders armeriaHeaders = new DefaultHttpHeaders(true, trailers.headerCount(), true);
                fillArmeriaHeaders(trailers, armeriaHeaders);
                responseWriter.write(armeriaHeaders);
            } else {
                HttpHeaders armeriaHeaders = new DefaultHttpHeaders();
                convertAndFillMetadata(trailers, armeriaHeaders);
                responseWriter.write(armeriaHeaders);
            }
            responseWriter.close();
        }

        @Override
        public void request(int numMessages) {
            transportState().requestMessagesFromDeframer(numMessages);
        }

        @Override
        public void cancel(Status status) {
            messageReader.cancel();
            responseWriter.close(status.getCause());
        }

        private void fillArmeriaHeaders(Metadata headers, HttpHeaders armeriaHeaders) {
            armeriaHeaders.status(HttpStatus.OK);
            armeriaHeaders.set(HttpHeaderNames.CONTENT_TYPE, GrpcUtil.CONTENT_TYPE_GRPC);
            convertAndFillMetadata(headers, armeriaHeaders);
        }

        private void convertAndFillMetadata(Metadata headers, HttpHeaders armeriaHeaders) {
            // GRPC only allows ascii in headers, and this utility converts into ascii bytes,
            // so we can use its result as is.
            byte[][] serializedMetadata = TransportFrameUtil.toHttp2Headers(headers);
            assert serializedMetadata.length % 2 == 0;
            for (int i = 0; i < serializedMetadata.length; i += 2) {
                armeriaHeaders.set(new AsciiString(serializedMetadata[i], false),
                                   new String(serializedMetadata[i + 1], StandardCharsets.US_ASCII));
            }
        }
    }

    class TransportState extends AbstractServerStream.TransportState {

        TransportState(StatsTraceContext statsCtx) {
            super((int) maxMessageSize, statsCtx);
        }

        @Override
        protected void deframeFailed(Throwable cause) {
            transportReportStatus(Status.fromThrowable(cause));
            messageReader.cancel();
        }

        @Override
        public void bytesRead(int numBytes) {
            // Armeria does flow control so we don't need to handle this here.
        }
    }
}
