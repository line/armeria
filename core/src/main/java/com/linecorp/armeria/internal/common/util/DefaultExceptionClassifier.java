/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.RequestCancellationException;

import io.netty.channel.ChannelException;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Exception.StreamException;

public final class DefaultExceptionClassifier implements ExceptionClassifier {

    private static final Pattern IGNORABLE_SOCKET_ERROR_MESSAGE = Pattern.compile(
            "(?:connection.*(?:reset|closed|abort|broken)|broken.*pipe)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IGNORABLE_HTTP2_ERROR_MESSAGE = Pattern.compile(
            "(?:stream closed)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IGNORABLE_TLS_ERROR_MESSAGE = Pattern.compile(
            "(?:closed already)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean isExpected(Throwable cause) {
        if (Flags.verboseSocketExceptions()) {
            return false;
        }
        if (cause instanceof ClosedChannelException || cause instanceof ClosedSessionException) {
            // Can happen when attempting to write to a channel closed by the other end.
            return true;
        }

        final String msg = cause.getMessage();
        if (msg != null) {
            if ((cause instanceof IOException || cause instanceof ChannelException) &&
                IGNORABLE_SOCKET_ERROR_MESSAGE.matcher(msg).find()) {
                // Can happen when socket error occurs.
                return true;
            }

            if (cause instanceof Http2Exception && IGNORABLE_HTTP2_ERROR_MESSAGE.matcher(msg).find()) {
                // Can happen when disconnected prematurely.
                return true;
            }

            if (cause instanceof SSLException && IGNORABLE_TLS_ERROR_MESSAGE.matcher(msg).find()) {
                // Can happen when disconnected prematurely.
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isStreamCancelling(Throwable cause) {
        return cause instanceof ClosedStreamException ||
               cause instanceof CancelledSubscriptionException ||
               cause instanceof RequestCancellationException ||
               cause instanceof WriteTimeoutException ||
               cause instanceof AbortedStreamException ||
               (cause instanceof StreamException &&
                ((StreamException) cause).error() == Http2Error.CANCEL);
    }
}
