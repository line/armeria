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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.ChannelUtil;

import io.netty.channel.Channel;

/**
 * Updates a {@link RequestLog} with newly available information.
 */
public interface RequestLogBuilder {

    /**
     * A dummy {@link RequestLogBuilder} that discards everything it collected.
     */
    RequestLogBuilder NOOP = new NoopRequestLogBuilder();

    /**
     * Adds the specified {@link RequestLog} so that the logs are propagated from the {@code child}.
     * Note that only the request-side logs of the first added child will be propagated. To fill the
     * response-side logs you need to call {@link #endResponseWithLastChild()}.
     */
    void addChild(RequestLog child);

    /**
     * Fills the response-side logs from the last added child. Note that already fulfilled
     * {@link RequestLogAvailability}s in the child log will be propagated immediately.
     */
    void endResponseWithLastChild();

    // Methods related with a request:

    /**
     * Starts the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#requestStartTimeMicros()}</li>
     *   <li>{@link RequestLog#requestStartTimeNanos()}</li>
     *   <li>{@link RequestLog#channel()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#authority()}</li>
     *   <li>{@link RequestLog#sslSession()}</li>
     * </ul>
     *
     * @param channel the {@link Channel} which handled the {@link Request}.
     * @param sessionProtocol the {@link SessionProtocol} of the connection.
     */
    default void startRequest(Channel channel, SessionProtocol sessionProtocol) {
        requireNonNull(channel, "channel");
        requireNonNull(sessionProtocol, "sessionProtocol");
        startRequest(channel, sessionProtocol, ChannelUtil.findSslSession(channel, sessionProtocol));
    }

    /**
     * Starts the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#requestStartTimeMicros()}</li>
     *   <li>{@link RequestLog#requestStartTimeNanos()}</li>
     *   <li>{@link RequestLog#channel()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#authority()}</li>
     *   <li>{@link RequestLog#sslSession()}</li>
     * </ul>
     *
     * @param channel the {@link Channel} which handled the {@link Request}.
     * @param sessionProtocol the {@link SessionProtocol} of the connection.
     * @param sslSession the {@link SSLSession} of the connection, or {@code null}.
     */
    void startRequest(Channel channel, SessionProtocol sessionProtocol, @Nullable SSLSession sslSession);

    /**
     * Starts the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#requestStartTimeMicros()}</li>
     *   <li>{@link RequestLog#requestStartTimeNanos()}</li>
     *   <li>{@link RequestLog#channel()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#authority()}</li>
     *   <li>{@link RequestLog#sslSession()}</li>
     * </ul>
     *
     * @param channel the {@link Channel} which handled the {@link Request}.
     * @param sessionProtocol the {@link SessionProtocol} of the connection.
     * @param requestStartTimeNanos {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch,
     *                               e.g. {@code System.currentTimeMillis() * 1000}.
     */
    default void startRequest(Channel channel, SessionProtocol sessionProtocol,
                              long requestStartTimeNanos, long requestStartTimeMicros) {
        requireNonNull(channel, "channel");
        requireNonNull(sessionProtocol, "sessionProtocol");
        startRequest(channel, sessionProtocol, ChannelUtil.findSslSession(channel, sessionProtocol),
                     requestStartTimeNanos, requestStartTimeMicros);
    }

    /**
     * Starts the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#requestStartTimeMicros()}</li>
     *   <li>{@link RequestLog#requestStartTimeNanos()}</li>
     *   <li>{@link RequestLog#channel()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#authority()}</li>
     *   <li>{@link RequestLog#sslSession()}</li>
     * </ul>
     *
     * @param channel the {@link Channel} which handled the {@link Request}.
     * @param sessionProtocol the {@link SessionProtocol} of the connection.
     * @param sslSession the {@link SSLSession} of the connection, or {@code null}.
     * @param requestStartTimeNanos {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch,
     *                               e.g. {@code System.currentTimeMillis() * 1000}.
     */
    void startRequest(Channel channel, SessionProtocol sessionProtocol, @Nullable SSLSession sslSession,
                      long requestStartTimeNanos, long requestStartTimeMicros);

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
     * Sets {@link RequestLog#requestFirstBytesTransferredTimeNanos()}.
     */
    void requestFirstBytesTransferred();

    /**
     * Sets {@link RequestLog#requestFirstBytesTransferredTimeNanos()} with the specified timestamp.
     */
    void requestFirstBytesTransferred(long requestFirstBytesTransferredNanos);

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
     * Finishes the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestEndTimeNanos()}</li>
     *   <li>{@link RequestLog#requestDurationNanos()}</li>
     *   <li>{@link RequestLog#requestCause()}</li>
     * </ul>
     */
    void endRequest();

    /**
     * Finishes the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestEndTimeNanos()}</li>
     *   <li>{@link RequestLog#requestDurationNanos()}</li>
     *   <li>{@link RequestLog#requestCause()}</li>
     * </ul>
     *
     * @param requestCause the cause of the failure.
     */
    void endRequest(Throwable requestCause);

    /**
     * Finishes the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestEndTimeNanos()}</li>
     *   <li>{@link RequestLog#requestDurationNanos()}</li>
     *   <li>{@link RequestLog#requestCause()}</li>
     * </ul>
     *
     * @param requestEndTimeNanos {@link System#nanoTime()} value when the request ended.
     */
    void endRequest(long requestEndTimeNanos);

    /**
     * Finishes the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestEndTimeNanos()}</li>
     *   <li>{@link RequestLog#requestDurationNanos()}</li>
     *   <li>{@link RequestLog#requestCause()}</li>
     * </ul>
     *
     * @param requestCause the cause of the failure.
     * @param requestEndTimeNanos {@link System#nanoTime()} value when the request ended.
     */
    void endRequest(Throwable requestCause, long requestEndTimeNanos);

    // Methods related with a response:

    /**
     * Starts the collection of {@link Response} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#responseStartTimeMillis()}</li>
     *   <li>{@link RequestLog#responseStartTimeMicros()}</li>
     *   <li>{@link RequestLog#responseStartTimeNanos()}</li>
     * </ul>
     */
    void startResponse();

    /**
     * Starts the collection of {@link Response} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#responseStartTimeMillis()}</li>
     *   <li>{@link RequestLog#responseStartTimeMicros()}</li>
     *   <li>{@link RequestLog#responseStartTimeNanos()}</li>
     * </ul>
     *
     * @param responseStartTimeNanos {@link System#nanoTime()} value when the response started.
     * @param responseStartTimeMicros the number of microseconds since the epoch,
     *                                e.g. {@code System.currentTimeMillis() * 1000}.
     */
    void startResponse(long responseStartTimeNanos, long responseStartTimeMicros);

    /**
     * Increases the {@link RequestLog#responseLength()} by {@code deltaBytes}.
     */
    void increaseResponseLength(long deltaBytes);

    /**
     * Sets the {@link RequestLog#responseLength()}.
     */
    void responseLength(long responseLength);

    /**
     * Sets {@link RequestLog#responseFirstBytesTransferredTimeNanos()}.
     */
    void responseFirstBytesTransferred();

    /**
     * Sets {@link RequestLog#responseFirstBytesTransferredTimeNanos()} with the specified timestamp.
     */
    void responseFirstBytesTransferred(long responseFirstBytesTransferredNanos);

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
     * Finishes the collection of the {@link Response} information. If a {@link Throwable} cause has been set
     * with {@link #responseContent(Object, Object)}, it will be treated as the {@code responseCause} for this
     * log. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#responseEndTimeNanos()}</li>
     *   <li>{@link RequestLog#responseDurationNanos()}</li>
     *   <li>{@link RequestLog#responseCause()}</li>
     * </ul>
     */
    void endResponse();

    /**
     * Finishes the collection of the {@link Response} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#responseEndTimeNanos()}</li>
     *   <li>{@link RequestLog#responseDurationNanos()}</li>
     *   <li>{@link RequestLog#responseCause()}</li>
     * </ul>
     *
     * @param responseCause the cause of the failure.
     */
    void endResponse(Throwable responseCause);

    /**
     * Finishes the collection of the {@link Response} information. If a {@link Throwable} cause has been set
     * with {@link #responseContent(Object, Object)}, it will be treated as the {@code responseCause} for this
     * log. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#responseEndTimeNanos()}</li>
     *   <li>{@link RequestLog#responseDurationNanos()}</li>
     *   <li>{@link RequestLog#responseCause()}</li>
     * </ul>
     *
     * @param responseEndTimeNanos {@link System#nanoTime()} value when the response ended.
     */
    void endResponse(long responseEndTimeNanos);

    /**
     * Finishes the collection of the {@link Response} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#responseEndTimeNanos()}</li>
     *   <li>{@link RequestLog#responseDurationNanos()}</li>
     *   <li>{@link RequestLog#responseCause()}</li>
     * </ul>
     *
     * @param responseCause the cause of the failure.
     * @param responseEndTimeNanos {@link System#nanoTime()} value when the response ended.
     */
    void endResponse(Throwable responseCause, long responseEndTimeNanos);
}
