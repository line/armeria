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

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;

final class DefaultGrpcExceptionHandlerFunction implements GrpcExceptionHandlerFunction {
    static final GrpcExceptionHandlerFunction INSTANCE = new DefaultGrpcExceptionHandlerFunction();

    @Override
    public Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
        return statusFromThrowable(cause);
    }

    /**
     * Converts the {@link Throwable} to a {@link Status}, taking into account exceptions specific to Armeria as
     * well and the protocol package.
     */
    private static Status statusFromThrowable(Throwable t) {
        final Status s = Status.fromThrowable(t);
        if (s.getCode() != Code.UNKNOWN) {
            return s;
        }

        if (t instanceof ClosedSessionException || t instanceof ClosedChannelException) {
            // ClosedChannelException is used any time the Netty channel is closed. Proper error
            // processing requires remembering the error that occurred before this one and using it
            // instead.
            return s;
        }
        if (t instanceof ClosedStreamException || t instanceof RequestTimeoutException) {
            return Status.CANCELLED.withCause(t);
        }
        if (t instanceof InvalidProtocolBufferException) {
            return Status.INVALID_ARGUMENT.withCause(t);
        }
        if (t instanceof UnprocessedRequestException ||
            t instanceof IOException ||
            t instanceof FailFastException) {
            return Status.UNAVAILABLE.withCause(t);
        }
        if (t instanceof Http2Exception) {
            if (t instanceof Http2Exception.StreamException &&
                ((Http2Exception.StreamException) t).error() == Http2Error.CANCEL) {
                return Status.CANCELLED;
            }
            return Status.INTERNAL.withCause(t);
        }
        if (t instanceof TimeoutException) {
            return Status.DEADLINE_EXCEEDED.withCause(t);
        }
        if (t instanceof ContentTooLargeException) {
            return Status.RESOURCE_EXHAUSTED.withCause(t);
        }
        return s;
    }

    private DefaultGrpcExceptionHandlerFunction() {}
}
