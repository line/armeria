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

package com.linecorp.armeria.internal.common.jsonrpc;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.sse.ServerSentEventBuilder;

public final class JsonRpcSseMessage implements JsonRpcMessage {

    private final JsonRpcMessage delegate;
    @Nullable
    private final String messageId;
    @Nullable
    private final String eventType;

    public JsonRpcSseMessage(JsonRpcMessage delegate, @Nullable String messageId, @Nullable String eventType) {
        this.delegate = delegate;
        this.messageId = messageId;
        this.eventType = eventType;
    }

    /**
     * Returns the message ID of the JSON-RPC message.
     * The message ID will be set as the {@link ServerSentEventBuilder#id(String)} when converted to SSE.
     */
    @Nullable
    public String messageId() {
        return messageId;
    }

    /**
     * Returns the event type of the JSON-RPC message.
     * The event type will be set as the {@link ServerSentEventBuilder#event(String)} when converted to SSE.
     */
    @Nullable
    public String eventType() {
        return eventType;
    }

    public JsonRpcMessage unwrap() {
        return delegate;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JsonRpcSseMessage)) {
            return false;
        }
        final JsonRpcSseMessage that = (JsonRpcSseMessage) o;
        return delegate.equals(that.delegate) &&
               Objects.equals(messageId, that.messageId) &&
               Objects.equals(eventType, that.eventType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate, messageId, eventType);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("delegate", delegate)
                          .add("messageId", messageId)
                          .add("eventType", eventType)
                          .toString();
    }
}
