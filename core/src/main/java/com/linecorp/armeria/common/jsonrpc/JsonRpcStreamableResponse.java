/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.jsonrpc;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.stream.StreamWriter;

/**
 * A streamable JSON-RPC response that lets you write multiple {@link JsonRpcRequest}s
 * and {@link JsonRpcNotification}s, then close the stream after emitting a final {@link JsonRpcResponse}.
 * A new instance can be created using {@link JsonRpcResponse#streaming()}.
 *
 * <p>This response is designed to be converted into Server-Sent Events (SSE) over HTTP, enabling streamable
 * HTTP responses in the Model Context Protocol (MCP).
 * See <a href="https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#sending-messages-to-the-server">the MCP specification</a>
 * for more information.
 */
@UnstableApi
public interface JsonRpcStreamableResponse extends StreamWriter<JsonRpcMessage>, JsonRpcResponse {

    /**
     * Returns whether a final {@link JsonRpcResponse} has been written to this stream.
     */
    default boolean hasFinalResponse() {
        return !isOpen();
    }

    /**
     * Tries to write the specified {@link JsonRpcMessage} to this stream with an optional message ID and event type.
     *
     * @param message the JSON-RPC message to write.
     *                The message will be set as the {@link ServerSentEvent#data()}
     * @param messageId the message ID of the JSON-RPC message.
     *                  The message ID will be set as the {@link ServerSentEvent#id()}
     *                  when converted to SSE.
     * @param eventType the event type of the JSON-RPC message.
     *                 The event type will be set as the {@link ServerSentEvent#event()}
     */
    boolean tryWrite(JsonRpcMessage message, @Nullable String messageId, @Nullable String eventType);

    /**
     * Writes the specified {@link JsonRpcMessage} to this stream with an optional message ID and event type.
     *
     * @param message the JSON-RPC message to write.
     *                The message will be set as the {@link ServerSentEvent#data()}
     * @param messageId the message ID of the JSON-RPC message.
     *                  The message ID will be set as the {@link ServerSentEvent#id()}
     *                  when converted to SSE.
     * @param eventType the event type of the JSON-RPC message.
     *                 The event type will be set as the {@link ServerSentEvent#event()}
     * @throws ClosedStreamException if the stream was already closed
     */
    default void write(JsonRpcMessage message, @Nullable String messageId, @Nullable String eventType) {
        if (!tryWrite(message, messageId, eventType)) {
            throw ClosedStreamException.get();
        }
    }
    /**
     * Closes this stream after writing the specified {@link JsonRpcResponse}.
     */
    default void close(JsonRpcResponse response) {
        close(response, null, null);
    }

    /**
     * Closes this stream after writing the specified {@link JsonRpcResponse} and an optional message ID and event type.
     *
     * @param response the final JSON-RPC response to write.
     *                 The response will be set as the {@link ServerSentEvent#data()}
     * @param messageId the message ID of the JSON-RPC message.
     *                  The message ID will be set as the {@link ServerSentEvent#id()}
     *                  when converted to SSE.
     * @param eventType the event type of the JSON-RPC message.
     *                 The event type will be set as the {@link ServerSentEvent#event()}
     */
    void close(JsonRpcResponse response, @Nullable String messageId, @Nullable String eventType);

    /**
     * Closes this stream.
     * Note that a {@link JsonRpcResponse} must be written as a final response before closing the stream.
     */
    @Override
    void close();

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     *
     * <pre>{@code
     * JsonRpcStreamableResponse streamableResponse = JsonRpcResponse.streaming();
     * // Write multiple JsonRpcMessages to the streamableResponse
     * streamableResponse.write(jsonRpcMessage);
     * ...
     * // Close the stream with a final JsonRpcResponse
     * streamableResponse.close(finalResponse);
     * // Now it's safe to call id()
     * Object id = streamableResponse.id();
     * assert streamableResponse.hasFinalResponse();
     * }</pre>
     */
    @Override
    @Nullable
    Object id();

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    default JsonRpcResponse withId(long id) {
        return JsonRpcResponse.super.withId(id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    default JsonRpcResponse withId(String id) {
        return JsonRpcResponse.super.withId(id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    JsonRpcResponse withId(Object id);

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    @Nullable
    Object result();

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    default boolean hasResult() {
        return JsonRpcResponse.super.hasResult();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    default boolean isSuccess() {
        return JsonRpcResponse.super.isSuccess();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    @Nullable
    JsonRpcError error();

    /**
     * {@inheritDoc}
     *
     * <p>Note that this method will throw an {@link IllegalStateException} if the stream has not been closed with
     * {@link #close(JsonRpcResponse)}. So make sure to call that method before invoking this method.
     * You can also use {@link #hasFinalResponse()} to check if the final response has been written.
     */
    @Override
    default boolean hasError() {
        return JsonRpcResponse.super.hasError();
    }
}
