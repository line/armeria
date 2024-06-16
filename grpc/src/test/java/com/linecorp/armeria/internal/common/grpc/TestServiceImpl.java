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

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;
import com.google.common.collect.Queues;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.Status;
import io.grpc.internal.LogExceptionRunnable;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.netty.util.AsciiString;
import testing.grpc.EmptyProtos;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages;
import testing.grpc.Messages.PayloadType;
import testing.grpc.Messages.ResponseParameters;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc;

public class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {

    public static final AsciiString EXTRA_HEADER_NAME = HttpHeaderNames.of("extra-header");

    public static final Key<String> EXTRA_HEADER_KEY = Key.of(EXTRA_HEADER_NAME.toString(),
                                                              Metadata.ASCII_STRING_MARSHALLER);

    public static final Key<StringValue> STRING_VALUE_KEY =
            ProtoUtils.keyForProto(StringValue.getDefaultInstance());

    private static final String UNCOMPRESSABLE_FILE =
            "/testing/grpc/uncompressable.bin";
    private final Random random = new Random();

    private final ScheduledExecutorService executor;
    private final ByteString uncompressableBuffer;
    private final ByteString compressableBuffer;

    /**
     * Constructs a controller using the given executor for scheduling response stream chunks.
     */
    public TestServiceImpl(ScheduledExecutorService executor) {
        this.executor = executor;
        compressableBuffer = ByteString.copyFrom(new byte[1024]);
        uncompressableBuffer = createBufferFromFile(UNCOMPRESSABLE_FILE);
    }

    @Override
    public void emptyCall(EmptyProtos.Empty empty,
                          StreamObserver<Empty> responseObserver) {
        ServiceRequestContext.current().mutateAdditionalResponseTrailers(
                mutator -> mutator.add(
                        STRING_VALUE_KEY.name(),
                        Base64.getEncoder().encodeToString(
                                StringValue.newBuilder().setValue("hello").build().toByteArray()) +
                        ',' +
                        Base64.getEncoder().encodeToString(
                                StringValue.newBuilder().setValue("world").build().toByteArray())));

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    /**
     * Immediately responds with a payload of the type and size specified in the request.
     */
    @Override
    public void unaryCall(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
        final ServerCallStreamObserver<SimpleResponse> obs =
                (ServerCallStreamObserver<SimpleResponse>) responseObserver;
        final SimpleResponse.Builder responseBuilder = SimpleResponse.newBuilder();
        try {
            switch (req.getResponseCompression()) {
                case DEFLATE:
                    // fallthrough, just use gzip
                case GZIP:
                    obs.setCompression("gzip");
                    break;
                case NONE:
                    obs.setCompression("identity");
                    break;
                case UNRECOGNIZED:
                    // fallthrough
                default:
                    obs.onError(Status.INVALID_ARGUMENT
                                        .withDescription("Unknown: " + req.getResponseCompression())
                                        .asRuntimeException());
                    return;
            }
        } catch (IllegalArgumentException e) {
            obs.onError(Status.UNIMPLEMENTED
                                .withDescription("compression not supported.")
                                .withCause(e)
                                .asRuntimeException());
            return;
        }

        if (req.getResponseSize() != 0) {
            final boolean compressable = compressableResponse(req.getResponseType());
            final ByteString dataBuffer = compressable ? compressableBuffer : uncompressableBuffer;
            // For consistency with the c++ TestServiceImpl, use a random offset for unary calls.
            // TODO(wonderfly): whether or not this is a good approach needs further discussion.
            final int offset = random.nextInt(
                    compressable ? compressableBuffer.size() : uncompressableBuffer.size());
            final ByteString payload = generatePayload(dataBuffer, offset, req.getResponseSize());
            responseBuilder.getPayloadBuilder()
                           .setType(compressable ? PayloadType.COMPRESSABLE : PayloadType.UNCOMPRESSABLE)
                           .setBody(payload);
        }

        if (req.hasResponseStatus()) {
            obs.onError(Status.fromCodeValue(req.getResponseStatus().getCode())
                              .withDescription(req.getResponseStatus().getMessage())
                              .asRuntimeException());
            return;
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    /**
     * Given a request that specifies chunk size and interval between responses, creates and schedules
     * the response stream.
     */
    @Override
    public void streamingOutputCall(StreamingOutputCallRequest request,
                                    StreamObserver<StreamingOutputCallResponse> responseObserver) {
        // Create and start the response dispatcher.
        new ResponseDispatcher(responseObserver).enqueue(toChunkQueue(request)).completeInput();
    }

    /**
     * Waits until we have received all of the request messages and then returns the aggregate payload
     * size for all of the received requests.
     */
    @Override
    public StreamObserver<Messages.StreamingInputCallRequest> streamingInputCall(
            final StreamObserver<Messages.StreamingInputCallResponse> responseObserver) {
        return new StreamObserver<StreamingInputCallRequest>() {
            private int totalPayloadSize;

            @Override
            public void onNext(StreamingInputCallRequest message) {
                totalPayloadSize += message.getPayload().getBody().size();
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(StreamingInputCallResponse.newBuilder()
                                                                  .setAggregatedPayloadSize(
                                                                          totalPayloadSize).build());
                responseObserver.onCompleted();
            }

            @Override
            public void onError(Throwable cause) {
                responseObserver.onError(cause);
            }
        };
    }

    /**
     * True bi-directional streaming. Processes requests as they come in. Begins streaming results
     * immediately.
     */
    @Override
    public StreamObserver<StreamingOutputCallRequest> fullDuplexCall(
            final StreamObserver<StreamingOutputCallResponse> responseObserver) {
        final ResponseDispatcher dispatcher = new ResponseDispatcher(responseObserver);
        return new StreamObserver<StreamingOutputCallRequest>() {
            @Override
            public void onNext(StreamingOutputCallRequest request) {
                if (request.hasResponseStatus()) {
                    dispatcher.cancel();
                    responseObserver.onError(Status.fromCodeValue(request.getResponseStatus().getCode())
                                                   .withDescription(
                                                           request.getResponseStatus().getMessage())
                                                   .asRuntimeException());
                    return;
                }
                dispatcher.enqueue(toChunkQueue(request));
            }

            @Override
            public void onCompleted() {
                if (!dispatcher.isCancelled()) {
                    // Tell the dispatcher that all input has been received.
                    dispatcher.completeInput();
                }
            }

            @Override
            public void onError(Throwable cause) {
                responseObserver.onError(cause);
            }
        };
    }

    /**
     * Similar to {@link #fullDuplexCall}, except that it waits for all streaming requests to be
     * received before starting the streaming responses.
     */
    @Override
    public StreamObserver<StreamingOutputCallRequest> halfDuplexCall(
            final StreamObserver<StreamingOutputCallResponse> responseObserver) {
        final Queue<Chunk> chunks = new LinkedList<Chunk>();
        return new StreamObserver<StreamingOutputCallRequest>() {
            @Override
            public void onNext(StreamingOutputCallRequest request) {
                chunks.addAll(toChunkQueue(request));
            }

            @Override
            public void onCompleted() {
                // Dispatch all of the chunks in one shot.
                new ResponseDispatcher(responseObserver).enqueue(chunks).completeInput();
            }

            @Override
            public void onError(Throwable cause) {
                responseObserver.onError(cause);
            }
        };
    }

    /**
     * Schedules the dispatch of a queue of chunks. Whenever chunks are added or input is completed,
     * the next response chunk is scheduled for delivery to the client. When no more chunks are
     * available, the stream is half-closed.
     */
    private class ResponseDispatcher {
        private final Chunk completionChunk = new Chunk(0, 0, 0, false);
        private final Queue<Chunk> chunks;
        private final StreamObserver<StreamingOutputCallResponse> responseStream;
        private boolean scheduled;
        @GuardedBy("this")
        private boolean cancelled;
        private Throwable failure;
        private final Runnable dispatchTask = new Runnable() {
            @Override
            public void run() {
                try {

                    // Dispatch the current chunk to the client.
                    try {
                        dispatchChunk();
                    } catch (RuntimeException e) {
                        // Indicate that nothing is scheduled and re-throw.
                        synchronized (ResponseDispatcher.this) {
                            scheduled = false;
                        }
                        throw e;
                    }

                    // Schedule the next chunk if there is one.
                    synchronized (ResponseDispatcher.this) {
                        // Indicate that nothing is scheduled.
                        scheduled = false;
                        scheduleNextChunk();
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        };

        ResponseDispatcher(StreamObserver<StreamingOutputCallResponse> responseStream) {
            chunks = Queues.newLinkedBlockingQueue();
            this.responseStream = responseStream;
        }

        /**
         * Adds the given chunks to the response stream and schedules the next chunk to be delivered if
         * needed.
         */
        synchronized ResponseDispatcher enqueue(Queue<Chunk> moreChunks) {
            assertNotFailed();
            chunks.addAll(moreChunks);
            scheduleNextChunk();
            return this;
        }

        /**
         * Indicates that the input is completed and the currently enqueued response chunks are all that
         * remain to be scheduled for dispatch to the client.
         */
        ResponseDispatcher completeInput() {
            assertNotFailed();
            chunks.add(completionChunk);
            scheduleNextChunk();
            return this;
        }

        /**
         * Allows the service to cancel the remaining responses.
         */
        synchronized void cancel() {
            Preconditions.checkState(!cancelled, "Dispatcher already cancelled");
            chunks.clear();
            cancelled = true;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        /**
         * Dispatches the current response chunk to the client. This is only called by the executor. At
         * any time, a given dispatch task should only be registered with the executor once.
         */
        private synchronized void dispatchChunk() {
            if (cancelled) {
                return;
            }
            try {
                // Pop off the next chunk and send it to the client.
                final Chunk chunk = chunks.remove();
                if (chunk == completionChunk) {
                    responseStream.onCompleted();
                } else {
                    responseStream.onNext(chunk.toResponse());
                }
            } catch (Throwable e) {
                failure = e;
                if (GrpcExceptionHandlerFunction.of()
                                                .apply(ServiceRequestContext.current(), e, new Metadata())
                                                .getCode() == Status.CANCELLED.getCode()) {
                    // Stream was cancelled by client, responseStream.onError() might be called already or
                    // will be called soon by inbounding StreamObserver.
                    chunks.clear();
                } else {
                    responseStream.onError(e);
                }
            }
        }

        /**
         * Schedules the next response chunk to be dispatched. If all input has been received and there
         * are no more chunks in the queue, the stream is closed.
         */
        private void scheduleNextChunk() {
            synchronized (this) {
                if (scheduled) {
                    // Dispatch task is already scheduled.
                    return;
                }

                // Schedule the next response chunk if there is one.
                final Chunk nextChunk = chunks.peek();
                if (nextChunk != null) {
                    scheduled = true;
                    // TODO(ejona): cancel future if RPC is cancelled
                    final Future<?> unused = executor.schedule(new LogExceptionRunnable(dispatchTask),
                                                               nextChunk.delayMicroseconds,
                                                               TimeUnit.MICROSECONDS);
                    return;
                }
            }
        }

        private void assertNotFailed() {
            if (failure != null) {
                throw new IllegalStateException("Stream already failed", failure);
            }
        }
    }

    /**
     * Breaks down the request and creates a queue of response chunks for the given request.
     */
    Queue<Chunk> toChunkQueue(StreamingOutputCallRequest request) {
        final Queue<Chunk> chunkQueue = new LinkedList<Chunk>();
        int offset = 0;
        final boolean compressable = compressableResponse(request.getResponseType());
        for (ResponseParameters params : request.getResponseParametersList()) {
            chunkQueue.add(new Chunk(params.getIntervalUs(), offset, params.getSize(), compressable));

            // Increment the offset past this chunk.
            // Both buffers need to be circular.
            offset = (offset + params.getSize()) % (
                    compressable ? compressableBuffer.size() : uncompressableBuffer.size());
        }
        return chunkQueue;
    }

    /**
     * A single chunk of a response stream. Contains delivery information for the dispatcher and can
     * be converted to a streaming response proto. A chunk just references it's payload in the
     * {@link #uncompressableBuffer} array. The payload isn't actually created until {@link
     * #toResponse()} is called.
     */
    private final class Chunk {
        private final int delayMicroseconds;
        private final int offset;
        private final int length;
        private final boolean compressable;

        private Chunk(int delayMicroseconds, int offset, int length, boolean compressable) {
            this.delayMicroseconds = delayMicroseconds;
            this.offset = offset;
            this.length = length;
            this.compressable = compressable;
        }

        /**
         * Convert this chunk into a streaming response proto.
         */
        private StreamingOutputCallResponse toResponse() {
            final StreamingOutputCallResponse.Builder responseBuilder =
                    StreamingOutputCallResponse.newBuilder();
            final ByteString dataBuffer = compressable ? compressableBuffer : uncompressableBuffer;
            final ByteString payload = generatePayload(dataBuffer, offset, length);
            responseBuilder.getPayloadBuilder()
                           .setType(compressable ? PayloadType.COMPRESSABLE : PayloadType.UNCOMPRESSABLE)
                           .setBody(payload);
            return responseBuilder.build();
        }
    }

    /**
     * Creates a buffer with data read from a file.
     */
    @SuppressWarnings("Finally") // Not concerned about suppression; expected to be exceedingly rare
    private ByteString createBufferFromFile(String fileClassPath) {
        ByteString buffer = ByteString.EMPTY;
        final InputStream inputStream = getClass().getResourceAsStream(fileClassPath);
        if (inputStream == null) {
            throw new IllegalArgumentException("Unable to locate file on classpath: " + fileClassPath);
        }

        try {
            buffer = ByteString.readFrom(inputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException ignorable) {
                // ignore
            }
        }
        return buffer;
    }

    /**
     * Indicates whether or not the response for this type should be compressable. If {@code RANDOM},
     * picks a random boolean.
     */
    private boolean compressableResponse(PayloadType responseType) {
        switch (responseType) {
            case COMPRESSABLE:
                return true;
            case RANDOM:
                return random.nextBoolean();
            case UNCOMPRESSABLE:
            default:
                return false;
        }
    }

    /**
     * Generates a payload of desired type and size. Reads compressableBuffer or
     * uncompressableBuffer as a circular buffer.
     */
    private static ByteString generatePayload(ByteString dataBuffer, int offset, int size) {
        ByteString payload = ByteString.EMPTY;
        // This offset would never pass the array boundary.
        int begin = offset;
        int end = 0;
        int bytesLeft = size;
        while (bytesLeft > 0) {
            end = Math.min(begin + bytesLeft, dataBuffer.size());
            // ByteString.substring returns the substring from begin, inclusive, to end, exclusive.
            payload = payload.concat(dataBuffer.substring(begin, end));
            bytesLeft -= end - begin;
            begin = end % dataBuffer.size();
        }
        return payload;
    }

    public static class EchoRequestHeadersInTrailers extends SimpleDecoratingHttpService {

        /**
         * Creates a new instance that decorates the specified {@link Service}.
         */
        public EchoRequestHeadersInTrailers(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req).mapTrailers(trailers -> {
                final String extraHeader = req.headers().get(EXTRA_HEADER_NAME);
                if (extraHeader != null) {
                    return trailers.toBuilder().set(EXTRA_HEADER_NAME, extraHeader).build();
                }
                return trailers;
            });
        }
    }
}
