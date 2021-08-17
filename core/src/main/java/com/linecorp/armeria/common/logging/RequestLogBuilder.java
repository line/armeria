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

import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.server.HttpService;

import io.netty.channel.Channel;

/**
 * Updates a {@link RequestLog} with newly available information.
 */
public interface RequestLogBuilder extends RequestLogAccess {

    // Methods related with a request:

    /**
     * Starts the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#requestStartTimeMicros()}</li>
     *   <li>{@link RequestLog#requestStartTimeNanos()}</li>
     * </ul>
     */
    default void startRequest() {
        startRequest(System.nanoTime(), SystemInfo.currentTimeMicros());
    }

    /**
     * Starts the collection of the {@link Request} information. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#requestStartTimeMillis()}</li>
     *   <li>{@link RequestLog#requestStartTimeMicros()}</li>
     *   <li>{@link RequestLog#requestStartTimeNanos()}</li>
     * </ul>
     *
     * @param requestStartTimeNanos {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch,
     *                               e.g. {@code System.currentTimeMillis() * 1000}.
     */
    void startRequest(long requestStartTimeNanos, long requestStartTimeMicros);

    /**
     * Sets the properties related with socket connection. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#channel()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#sslSession()}</li>
     * </ul>
     *
     * @param channel the {@link Channel} which handled the {@link Request}.
     * @param sessionProtocol the {@link SessionProtocol} of the connection.
     */
    void session(@Nullable Channel channel, SessionProtocol sessionProtocol,
                 @Nullable ClientConnectionTimings connectionTimings);

    /**
     * Sets the properties related with socket connection. This method sets the following properties:
     * <ul>
     *   <li>{@link RequestLog#channel()}</li>
     *   <li>{@link RequestLog#sessionProtocol()}</li>
     *   <li>{@link RequestLog#sslSession()}</li>
     * </ul>
     *
     * @param channel the {@link Channel} which handled the {@link Request}.
     * @param sessionProtocol the {@link SessionProtocol} of the connection.
     * @param sslSession the {@link SSLSession} of the connection, or {@code null}.
     */
    void session(@Nullable Channel channel, SessionProtocol sessionProtocol, @Nullable SSLSession sslSession,
                 @Nullable ClientConnectionTimings connectionTimings);

    /**
     * Sets the {@link SerializationFormat}.
     */
    void serializationFormat(SerializationFormat serializationFormat);

    /**
     * Sets the human-readable service name and method name of the {@link Request} such as:
     * <ul>
     *    <li>A service and method name for gRPC and Thrift</li>
     *    <li>An innermost class and method name for annotated service</li>
     *    <li>A path pattern and HTTP method name for {@link HttpService}</li>
     * </ul>
     * This property is often used as a meter tag or distributed trace's span name.
     */
    void name(String serviceName, String name);

    /**
     * Sets the human-readable name of the {@link Request}, such as RPC method name, annotated service method
     * name or HTTP method name. This property is often used as a meter tag or distributed trace's span name.
     */
    void name(String name);

    /**
     * Increases the {@link RequestLog#requestLength()} by {@code deltaBytes}.
     */
    void increaseRequestLength(long deltaBytes);

    /**
     * Increases the {@link RequestLog#requestLength()} by {@code data.length()} and passes {@code data}
     * to the previewer.
     */
    void increaseRequestLength(HttpData data);

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
    void requestHeaders(RequestHeaders requestHeaders);

    /**
     * Sets the {@link RequestLog#requestContent()} and the {@link RequestLog#rawRequestContent()}.
     * If the specified {@code requestContent} is an {@link RpcRequest} and
     * the {@link RequestContext#rpcRequest()} is {@code null}, this method will call
     * {@link RequestContext#updateRpcRequest(RpcRequest)}.
     */
    void requestContent(@Nullable Object requestContent, @Nullable Object rawRequestContent);

    /**
     * Sets the {@link RequestLog#requestContentPreview()}.
     */
    void requestContentPreview(@Nullable String requestContentPreview);

    /**
     * Sets the {@link RequestLog#requestTrailers()}.
     */
    void requestTrailers(HttpHeaders requestTrailers);

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
     * Increases the {@link RequestLog#responseLength()} by {@code data.length()} and passes {@code data}
     * to the previewer.
     */
    void increaseResponseLength(HttpData data);

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
    void responseHeaders(ResponseHeaders responseHeaders);

    /**
     * Sets the {@link RequestLog#responseContent()} and the {@link RequestLog#rawResponseContent()}.
     */
    void responseContent(@Nullable Object responseContent, @Nullable Object rawResponseContent);

    /**
     * Sets the {@link RequestLog#responseContentPreview()}.
     */
    void responseContentPreview(@Nullable String responseContentPreview);

    /**
     * Sets the {@link RequestLog#responseTrailers()}.
     */
    void responseTrailers(HttpHeaders responseTrailers);

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

    // Methods related with deferred properties

    /**
     * Returns {@code true} if the specified {@link RequestLogProperty} has been deferred with
     * {@link #defer(RequestLogProperty)}.
     */
    boolean isDeferred(RequestLogProperty property);

    /**
     * Returns {@code true} if all of the specified {@link RequestLogProperty}s have been deferred with
     * {@link #defer(RequestLogProperty)}.
     */
    boolean isDeferred(RequestLogProperty... properties);

    /**
     * Returns {@code true} if all of the specified {@link RequestLogProperty}s have been deferred with
     * {@link #defer(RequestLogProperty)}.
     */
    boolean isDeferred(Iterable<RequestLogProperty> properties);

    /**
     * Allows setting the specified {@link RequestLogProperty} even after {@link #endResponse()} or
     * {@link #endResponse(Throwable)} called. Once this method is called, the caller must ensure to call
     * the setter of the specified {@link RequestLogProperty}, because otherwise the {@link RequestLog}
     * will never be completed.
     *
     * <p>Note, however, the following {@link RequestLogProperty}s will be set automatically when the
     * {@link RequestLog} has ended with an exception:
     * <ul>
     *   <li>{@link RequestLogProperty#REQUEST_CONTENT}</li>
     *   <li>{@link RequestLogProperty#REQUEST_CONTENT_PREVIEW}</li>
     *   <li>{@link RequestLogProperty#RESPONSE_CONTENT}</li>
     *   <li>{@link RequestLogProperty#RESPONSE_CONTENT_PREVIEW}</li>
     * </ul>
     */
    void defer(RequestLogProperty property);

    /**
     * Allows setting the specified {@link RequestLogProperty}s even after {@link #endResponse()} or
     * {@link #endResponse(Throwable)} called. Once this method is called, the caller must ensure to call
     * the setters of the specified {@link RequestLogProperty}s, because otherwise the {@link RequestLog}
     * will never be completed.
     *
     * <p>Note, however, the following {@link RequestLogProperty}s will be set automatically when the
     * {@link RequestLog} has ended with an exception:
     * <ul>
     *   <li>{@link RequestLogProperty#REQUEST_CONTENT}</li>
     *   <li>{@link RequestLogProperty#REQUEST_CONTENT_PREVIEW}</li>
     *   <li>{@link RequestLogProperty#RESPONSE_CONTENT}</li>
     *   <li>{@link RequestLogProperty#RESPONSE_CONTENT_PREVIEW}</li>
     * </ul>
     */
    void defer(RequestLogProperty... properties);

    /**
     * Allows setting the specified {@link RequestLogProperty}s even after {@link #endResponse()} or
     * {@link #endResponse(Throwable)} called. Once this method is called, the caller must ensure to call
     * the setters of the specified {@link RequestLogProperty}s, because otherwise the {@link RequestLog}
     * will never be completed.
     *
     * <p>Note, however, the following {@link RequestLogProperty}s will be set automatically when the
     * {@link RequestLog} has ended with an exception:
     * <ul>
     *   <li>{@link RequestLogProperty#REQUEST_CONTENT}</li>
     *   <li>{@link RequestLogProperty#REQUEST_CONTENT_PREVIEW}</li>
     *   <li>{@link RequestLogProperty#RESPONSE_CONTENT}</li>
     *   <li>{@link RequestLogProperty#RESPONSE_CONTENT_PREVIEW}</li>
     * </ul>
     */
    void defer(Iterable<RequestLogProperty> properties);

    // Methods related with nested logs

    /**
     * Adds the specified {@link RequestLogAccess} so that the logs are propagated from the {@code child}.
     * Note that only the request-side logs of the first added child will be propagated. To fill the
     * response-side logs you need to call {@link #endResponseWithLastChild()}.
     */
    void addChild(RequestLogAccess child);

    /**
     * Fills the response-side logs from the last added child. Note that already collected properties
     * in the child log will be propagated immediately.
     */
    void endResponseWithLastChild();
}
