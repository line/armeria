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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.internal.common.jsonrpc.JsonRpcSseMessage;

@SuppressWarnings("deprecation")
final class DefaultJsonRpcStreamableResponse extends DefaultStreamMessage<JsonRpcMessage>
        implements JsonRpcStreamableResponse {

    @Nullable
    private JsonRpcResponse finalResponse;

    @Override
    public boolean tryWrite(JsonRpcMessage message) {
        return tryWrite(message, null, null);
    }

    @Override
    public boolean tryWrite(JsonRpcMessage message, @Nullable String messageId, @Nullable String eventType) {
        requireNonNull(message, "message");
        JsonRpcMessage maybeWrapped = message;
        if (messageId != null) {
            maybeWrapped = new JsonRpcSseMessage(message, messageId, eventType);
        }
        if (message instanceof JsonRpcResponse) {
            if (super.tryWrite(maybeWrapped) && tryClose()) {
                finalResponse = (JsonRpcResponse) message;
                return true;
            } else {
                return false;
            }
        } else {
            return super.tryWrite(maybeWrapped);
        }
    }

    @Override
    public void close(JsonRpcResponse response, @Nullable String messageId, @Nullable String eventType) {
        requireNonNull(response, "response");
        if (!tryWrite(response, messageId, eventType)) {
            throw new ClosedStreamException("Failed to close the stream with a final JsonRpcResponse.");
        }
    }

    @Override
    public @Nullable Object id() {
        return ensureResponseWritten().id();
    }

    @Override
    public JsonRpcResponse withId(Object id) {
        return ensureResponseWritten().withId(id);
    }

    @Override
    public @Nullable Object result() {
        return ensureResponseWritten().result();
    }

    @Override
    public @Nullable JsonRpcError error() {
        return ensureResponseWritten().error();
    }

    private JsonRpcResponse ensureResponseWritten() {
        if (finalResponse == null) {
            throw new IllegalStateException("No final JsonRpcResponse has been written.");
        }
        return finalResponse;
    }
}
