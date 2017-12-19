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
package com.linecorp.armeria.server.tomcat;

import java.io.IOException;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.tomcat.util.buf.ByteChunk;

import com.linecorp.armeria.common.HttpData;

class Tomcat80InputBuffer implements InputBuffer {
    private final HttpData content;
    private boolean read;

    Tomcat80InputBuffer(HttpData content) {
        this.content = content;
    }

    @Override
    public int doRead(ByteChunk chunk, Request request) throws IOException {
        if (read || content.isEmpty()) {
            // Read only once.
            return -1;
        }

        read = true;

        final int readableBytes = content.length();
        chunk.setBytes(content.array(), content.offset(), readableBytes);

        return readableBytes;
    }
}
