/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.internal.server.tomcat;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.InputBuffer;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.ApplicationBufferHandler;

import com.linecorp.armeria.common.HttpData;

public final class Tomcat90InputBuffer implements InputBuffer {
    private final HttpData content;
    private boolean read;

    public Tomcat90InputBuffer(HttpData content) {
        this.content = content;
    }

    // Required for 8.5.
    public int doRead(ByteChunk chunk) {
        if (!isNeedToRead()) {
            // Read only once.
            return -1;
        }

        read = true;

        final int readableBytes = content.length();
        chunk.setBytes(content.array(), 0, readableBytes);

        return readableBytes;
    }

    @Override
    public int doRead(ApplicationBufferHandler handler) throws IOException {
        if (!isNeedToRead()) {
            // Read only once.
            return -1;
        }
        read = true;

        final int readableBytes = content.length();
        handler.setByteBuffer(ByteBuffer.wrap(content.array(), 0, readableBytes));
        return readableBytes;
    }

    private boolean isNeedToRead() {
        return !(read || content.isEmpty());
    }
}
