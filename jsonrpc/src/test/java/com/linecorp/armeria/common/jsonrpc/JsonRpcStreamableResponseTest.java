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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.jsonrpc.JsonRpcSseMessage;

import reactor.test.StepVerifier;

class JsonRpcStreamableResponseTest {

    @Test
    void shouldAccessFinalResult() {
        final JsonRpcStreamableResponse stream = JsonRpcResponse.streaming();
        assertThatThrownBy(stream::id)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No final JsonRpcResponse has been written.");
        assertThatThrownBy(stream::result)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No final JsonRpcResponse has been written.");
        stream.close(JsonRpcResponse.ofSuccess(1, "the end"));
        assertThat(stream.id()).isEqualTo(1);
        assertThat(stream.result()).isEqualTo("the end");
    }

    @Test
    void shouldDisallowWritingAfterFinalResponse() {
        final JsonRpcStreamableResponse stream = JsonRpcResponse.streaming();
        stream.write(JsonRpcResponse.ofSuccess(1, "part 1"));
        assertThatThrownBy(() -> stream.write(JsonRpcResponse.ofSuccess(2, "part 2")))
                .isInstanceOf(ClosedStreamException.class);
    }

    @Test
    void shouldStreamMessages() {
        final JsonRpcStreamableResponse stream = JsonRpcResponse.streaming();
        stream.write(JsonRpcNotification.of("foo"));
        stream.write(JsonRpcRequest.of(1, "bar"));
        stream.write(JsonRpcNotification.of("baz"));
        stream.write(JsonRpcRequest.of(2, "qux"));
        stream.close(JsonRpcResponse.ofSuccess(3, "done"));
        StepVerifier.create(stream)
                    .expectNext(JsonRpcNotification.of("foo"))
                    .expectNext(JsonRpcRequest.of(1, "bar"))
                    .expectNext(JsonRpcNotification.of("baz"))
                    .expectNext(JsonRpcRequest.of(2, "qux"))
                    .expectNext(JsonRpcResponse.ofSuccess(3, "done"))
                    .verifyComplete();
    }

    @Test
    void shouldKeepMessageIdAndType() {
        final JsonRpcStreamableResponse stream = JsonRpcResponse.streaming();
        assertThat(stream.tryWrite(JsonRpcNotification.of("foo"), "msg-1", "notification"))
                .isTrue();
        assertThat(stream.tryWrite(JsonRpcRequest.of(1, "bar"), "msg-2", "request"))
                .isTrue();
        stream.close(JsonRpcResponse.ofSuccess(2, "done"), "msg-3", "response");

        StepVerifier
                .create(stream)
                .expectNext(new JsonRpcSseMessage(JsonRpcNotification.of("foo"), "msg-1", "notification"))
                .expectNext(new JsonRpcSseMessage(JsonRpcRequest.of(1, "bar"), "msg-2", "request"))
                .expectNext(new JsonRpcSseMessage(JsonRpcResponse.ofSuccess(2, "done"), "msg-3", "response"))
                .verifyComplete();
    }
}
