/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

/**
 * Updates a {@link RequestLog} with newly available information.
 */
public interface RequestLogBuilder {

    /**
     * A dummy {@link RequestLogBuilder} that discards everything it collected.
     */
    RequestLogBuilder NOOP = new NoopRequestLogBuilder();

    // Methods related with a request:

    /**
     * Starts the collection of information for the {@link Request}. This method sets the following
     * properties:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#host()}</li>
     * </ul>
     */
    void startRequest(Channel channel, SessionProtocol sessionProtocol, String host);

    /**
     * Sets the {@link SerializationFormat}.
     */
    void serializationFormat(SerializationFormat serializationFormat);

    /**
     * Increases the {@link RequestLog#requestLength()} by {@code deltaBytes}.
     */
    void increaseRequestLength(long deltaBytes);

    /**
     * Sets the {@link RequestLog#requestLength()}.
     */
    void requestLength(long requestLength);

    /**
     * Sets the {@link RequestLog#requestHeaders()}.
     */
    void requestHeaders(HttpHeaders requestHeaders);

    /**
     * Sets the {@link RequestLog#requestContent()} and the {@link RequestLog#rawRequestContent()}.
     */
    void requestContent(@Nullable Object requestContent, @Nullable Object rawRequestContent);

    /**
     * Allows the {@link #requestContent(Object, Object)} called after {@link #endRequest()}.
     * By default, if {@link #requestContent(Object, Object)} was not called yet, {@link #endRequest()} will
     * call {@code requestContent(null, null)} automatically. This method turns off this default behavior.
     * Note, however, this method will not prevent {@link #endRequest(Throwable)} from calling
     * {@code requestContent(null, null)} automatically.
     */
    void deferRequestContent();

    /**
     * Returns {@code true} if {@link #deferRequestContent()} was ever called.
     */
    boolean isRequestContentDeferred();

    /**
     * Sets {@link RequestLog#requestDurationNanos()} and finishes the collection of the information.
     */
    void endRequest();

    /**
     * Sets {@link RequestLog#requestDurationNanos()} and finishes the collection of the information.
     */
    void endRequest(Throwable requestCause);

    // Methods related with a response:

    /**
     * Starts the collection of information for the {@link Response}. This method sets
     * {@link RequestLog#responseStartTimeMillis()}.
     */
    void startResponse();

    /**
     * Increases the {@link RequestLog#responseLength()} by {@code deltaBytes}.
     */
    void increaseResponseLength(long deltaBytes);

    /**
     * Sets the {@link RequestLog#responseLength()}.
     */
    void responseLength(long responseLength);

    /**
     * Sets the {@link RequestLog#responseHeaders()}.
     */
    void responseHeaders(HttpHeaders responseHeaders);

    /**
     * Sets the {@link RequestLog#responseContent()} and the {@link RequestLog#rawResponseContent()}.
     */
    void responseContent(@Nullable Object responseContent, @Nullable Object rawResponseContent);

    /**
     * Allows the {@link #responseContent(Object, Object)} called after {@link #endResponse()}.
     * By default, if {@link #responseContent(Object, Object)} was not called yet, {@link #endResponse()} will
     * call {@code responseContent(null, null)} automatically. This method turns off this default behavior.
     * Note, however, this method will not prevent {@link #endResponse(Throwable)} from calling
     * {@code responseContent(null, null)} automatically.
     */
    void deferResponseContent();

    /**
     * Returns {@code true} if {@link #deferResponseContent()} was ever called.
     */
    boolean isResponseContentDeferred();

    /**
     * Sets {@link RequestLog#responseDurationNanos()} and finishes the collection of the information.
     */
    void endResponse();

    /**
     * Sets {@link RequestLog#responseDurationNanos()} and finishes the collection of the information.
     */
    void endResponse(Throwable responseCause);
}
