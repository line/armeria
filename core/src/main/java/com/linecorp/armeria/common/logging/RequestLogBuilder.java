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

package com.linecorp.armeria.common.logging;

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
     *   <li>{@link RequestLog#method()}</li>
     *   <li>{@link RequestLog#path()}</li>
     * </ul>
     */
    void startRequest(
            Channel channel, SessionProtocol sessionProtocol, String host, String method, String path);

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
     * Sets the {@link RequestLog#requestEnvelope()}.
     */
    void requestEnvelope(Object requestEnvelope);

    /**
     * Sets the {@link RequestLog#requestContent()}.
     */
    void requestContent(Object requestContent);

    /**
     * Allows the {@link #requestContent(Object)} called after {@link #endRequest()}.
     * By default, if {@link #requestContent(Object)} was not called yet, {@link #endRequest()} will call
     * {@code requestContent(null)} automatically. This method turns off this default behavior.
     * Note, however, this method will not prevent {@link #endRequest(Throwable)} from calling
     * {@code requestContent(null)} automatically.
     */
    void deferRequestContent();

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
     * Sets the status code specific to the current {@link SessionProtocol}.
     */
    void statusCode(int statusCode);

    /**
     * Increases the {@link RequestLog#responseLength()} by {@code deltaBytes}.
     */
    void increaseResponseLength(long deltaBytes);

    /**
     * Sets the {@link RequestLog#responseLength()}.
     */
    void responseLength(long responseLength);

    /**
     * Sets the {@link RequestLog#responseEnvelope()}.
     */
    void responseEnvelope(Object responseEnvelope);

    /**
     * Sets the {@link RequestLog#responseContent()}.
     */
    void responseContent(Object responseContent);

    /**
     * Allows the {@link #responseContent(Object)} called after {@link #endResponse()}.
     * By default, if {@link #responseContent(Object)} was not called yet, {@link #endResponse()} will call
     * {@code responseContent(null)} automatically. This method turns off this default behavior.
     * Note, however, this method will not prevent {@link #endResponse(Throwable)} from calling
     * {@code responseContent(null)} automatically.
     */
    void deferResponseContent();

    /**
     * Sets {@link RequestLog#responseDurationNanos()} and finishes the collection of the information.
     */
    void endResponse();

    /**
     * Sets {@link RequestLog#responseDurationNanos()} and finishes the collection of the information.
     */
    void endResponse(Throwable responseCause);
}
