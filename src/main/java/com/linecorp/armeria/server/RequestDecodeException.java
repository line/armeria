/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.server;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;

/**
 * A {@link RuntimeException} raised when
 * {@link ServiceCodec#decodeRequest(Channel, SessionProtocol, String, String, String, ByteBuf, Object, Promise)
 * ServiceCodec.decodeRequest()} failed to decode an invocation request.
 */
public class RequestDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1268757990666737813L;

    private final int errorResponseSizeBytes;

    /**
     * Creates a new instance with the specified {@code errorResponseSizeBytes}.
     */
    public RequestDecodeException(int errorResponseSizeBytes) {
        this.errorResponseSizeBytes = errorResponseSizeBytes;
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause} and
     * {@code errorResponseSizeBytes}.
     */
    public RequestDecodeException(String message, Throwable cause, int errorResponseSizeBytes) {
        super(message, cause);
        this.errorResponseSizeBytes = errorResponseSizeBytes;
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code errorResponseSizeBytes}.
     */
    public RequestDecodeException(String message, int errorResponseSizeBytes) {
        super(message);
        this.errorResponseSizeBytes = errorResponseSizeBytes;
    }

    /**
     * Creates a new instance with the specified {@code cause} and {@code errorResponseSizeBytes}.
     */
    public RequestDecodeException(Throwable cause, int errorResponseSizeBytes) {
        super(cause);
        this.errorResponseSizeBytes = errorResponseSizeBytes;
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause}, suppression enabled or
     * disabled, writable stack trace enabled or disabled, and {@code errorResponseSizeBytes}.
     */
    protected RequestDecodeException(String message, Throwable cause, boolean enableSuppression,
                                     boolean writableStackTrace, int errorResponseSizeBytes) {
        super(message, cause, enableSuppression, writableStackTrace);
        this.errorResponseSizeBytes = errorResponseSizeBytes;
    }

    /**
     * Returns the number of bytes of the error response associated with this exception.
     */
    public int errorResponseSizeBytes() {
        return errorResponseSizeBytes;
    }
}
