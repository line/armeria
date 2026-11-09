/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.armeria.common.sse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;

final class ServerSentEventDecoder implements HttpDecoder<ServerSentEvent> {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean firstBlock = true;
    private boolean lineEmpty = true;
    private boolean skipLineFeed;

    @Override
    public void process(StreamDecoderInput in, StreamDecoderOutput<ServerSentEvent> out) {
        while (in.isReadable()) {
            final byte value = in.readByte();
            if (skipLineFeed) {
                skipLineFeed = false;
                if (value == '\n') {
                    // A CRLF pair is one line ending, even across input chunks.
                    continue;
                }
            }
            buffer.write(value);
            if (value == '\r' || value == '\n') {
                // A line ending on an already empty line closes the event block.
                if (lineEmpty) {
                    // Decode a complete block so UTF-8 characters split across chunks remain intact.
                    final ServerSentEvent event = new ServerSentEventParser(
                            new String(buffer.toByteArray(), StandardCharsets.UTF_8), firstBlock).parse();
                    // Only the stream's first block may contain an ignorable leading BOM.
                    firstBlock = false;
                    // toByteArray() copied the bytes, so the buffer can be reused safely.
                    buffer.reset();
                    if (event != ServerSentEvent.empty()) {
                        out.add(event);
                    }
                }
                lineEmpty = true;
                skipLineFeed = value == '\r';
            } else {
                lineEmpty = false;
            }
        }
    }
}
