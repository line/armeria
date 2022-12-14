/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.common.websocket;

import com.linecorp.armeria.common.stream.DefaultStreamMessage;

final class DefaultWebSocket extends DefaultStreamMessage<WebSocketFrame> implements WebSocketWriter {

    @Override
    public boolean tryWrite(WebSocketFrame obj) {
        final boolean written = super.tryWrite(obj);
        if (written && obj.type() == WebSocketFrameType.CLOSE) {
            // Close the stream if a close frame is written.
            super.close();
        }
        return written;
    }

    @Override
    public void write(String text, boolean finalFragment) {
        write(WebSocketFrame.ofText(text, finalFragment));
    }

    @Override
    public void write(byte[] data, boolean finalFragment) {
        write(WebSocketFrame.ofBinary(data, finalFragment));
    }

    @Override
    public void ping() {
        write(WebSocketFrame.ofPing());
    }

    @Override
    public void ping(byte[] data) {
        write(WebSocketFrame.ofPing(data));
    }

    @Override
    public void pong() {
        write(WebSocketFrame.ofPong());
    }

    @Override
    public void pong(byte[] data) {
        write(WebSocketFrame.ofPong(data));
    }

    @Override
    public void close() {
        close(WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    @Override
    public void close(WebSocketCloseStatus status) {
        close(WebSocketFrame.ofClose(status));
    }

    @Override
    public void close(WebSocketCloseStatus status, String reasonPhrase) {
        close(WebSocketFrame.ofClose(status, reasonPhrase));
    }

    private void close(CloseWebSocketFrame closeFrame) {
        // There's chance that the stream is already closed, so we use tryWrite.
        final boolean ignored = tryWrite(closeFrame);
    }
}
