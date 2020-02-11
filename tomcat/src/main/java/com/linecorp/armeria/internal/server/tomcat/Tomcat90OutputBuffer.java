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
import java.util.Arrays;
import java.util.Queue;

import org.apache.coyote.OutputBuffer;
import org.apache.tomcat.util.buf.ByteChunk;

import com.linecorp.armeria.common.HttpData;

public final class Tomcat90OutputBuffer implements OutputBuffer {
    private final Queue<HttpData> data;
    private long bytesWritten;

    public Tomcat90OutputBuffer(Queue<HttpData> data) {
        this.data = data;
    }

    // Required by Tomcat 8.5
    public int doWrite(ByteChunk chunk) {
        final int start = chunk.getStart();
        final int end = chunk.getEnd();
        final int length = end - start;
        if (length == 0) {
            return 0;
        }

        // NB: We make a copy because Tomcat reuses the underlying byte array of 'chunk'.
        final byte[] content = Arrays.copyOfRange(chunk.getBuffer(), start, end);

        data.add(HttpData.wrap(content));

        bytesWritten += length;
        return length;
    }

    @Override
    public int doWrite(ByteBuffer chunk) throws IOException {
        final int length = chunk.remaining();
        if (length <= 0) {
            return 0;
        }

        // NB: We make a copy because Tomcat reuses the underlying byte array of 'chunk'.
        final byte[] content = new byte[chunk.remaining()];
        chunk.get(content);

        data.add(HttpData.wrap(content));

        bytesWritten += length;
        return length;
    }

    @Override
    public long getBytesWritten() {
        return bytesWritten;
    }
}
