/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.grpc;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;

enum DefaultGrpcExceptionHandlerFunction implements GrpcExceptionHandlerFunction {
    INSTANCE;

    /**
     * Converts the {@link Throwable} to a {@link Status}, taking into account exceptions specific to Armeria as
     * well and the protocol package.
     */
    @Override
    public Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
        final Status s = Status.fromThrowable(cause);
        if (s.getCode() != Code.UNKNOWN) {
            return s;
        }

        if (cause instanceof ClosedSessionException || cause instanceof ClosedChannelException) {
            if (ctx instanceof ServiceRequestContext) {
                // Upstream uses CANCELLED
                // https://github.com/grpc/grpc-java/blob/2c83ef06327adabd8e234850a5dc9dbd9ac063b0/stub/src/main/java/io/grpc/stub/ServerCalls.java#L289-L291
                return Status.CANCELLED.withCause(cause);
            }
            // ClosedChannelException is used any time the Netty channel is closed. Proper error
            // processing requires remembering the error that occurred before this one and using it
            // instead.
            return s;
        }
        if (cause instanceof ClosedStreamException || cause instanceof RequestTimeoutException) {
            return Status.CANCELLED.withCause(cause);
        }
        if (cause instanceof InvalidProtocolBufferException) {
            return Status.INVALID_ARGUMENT.withCause(cause);
        }
        if (cause instanceof UnprocessedRequestException ||
            cause instanceof IOException ||
            cause instanceof FailFastException) {
            return Status.UNAVAILABLE.withCause(cause);
        }
        if (cause instanceof Http2Exception) {
            if (cause instanceof Http2Exception.StreamException &&
                ((Http2Exception.StreamException) cause).error() == Http2Error.CANCEL) {
                return Status.CANCELLED;
            }
            return Status.INTERNAL.withCause(cause);
        }
        if (cause instanceof TimeoutException) {
            return Status.DEADLINE_EXCEEDED.withCause(cause);
        }
        if (cause instanceof ContentTooLargeException) {
            return Status.RESOURCE_EXHAUSTED.withCause(cause);
        }
        return s;
    }
}
