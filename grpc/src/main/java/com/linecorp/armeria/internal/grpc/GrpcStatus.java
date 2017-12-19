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

package com.linecorp.armeria.internal.grpc;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import com.linecorp.armeria.client.ResponseTimeoutException;

import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Exception.StreamException;

/**
 * Utilities for handling {@link Status} in Armeria.
 */
public final class GrpcStatus {

    /**
     * Converts the {@link Throwable} to a {@link Status}, taking into account exceptions specific to Armeria as
     * well.
     */
    public static Status fromThrowable(Throwable t) {
        requireNonNull(t, "t");
        Status s = Status.fromThrowable(t);
        if (s.getCode() != Code.UNKNOWN) {
            return s;
        }
        if (t instanceof StreamException) {
            StreamException streamException = (StreamException) t;
            if (streamException.getMessage() != null && streamException.getMessage().contains("RST_STREAM")) {
                return Status.CANCELLED;
            }
        }
        if (t instanceof ClosedChannelException) {
            // ClosedChannelException is used any time the Netty channel is closed. Proper error
            // processing requires remembering the error that occurred before this one and using it
            // instead.
            return Status.UNKNOWN.withCause(t);
        }
        if (t instanceof IOException) {
            return Status.UNAVAILABLE.withCause(t);
        }
        if (t instanceof Http2Exception) {
            return Status.INTERNAL.withCause(t);
        }
        if (t instanceof ResponseTimeoutException) {
            return Status.DEADLINE_EXCEEDED.withCause(t);
        }
        return s;
    }

    private GrpcStatus() {}
}
