/*
 * Copyright 2019 LINE Corporation
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
/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.common.grpc.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.linecorp.armeria.common.util.UnstableApi;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Utility to escape status messages (e.g., error messages) for saving to ascii headers.
 */
@UnstableApi
public final class StatusMessageEscaper {

    private static final byte[] HEX =
            { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

    /**
     * Escape the provided unicode {@link String} into ascii.
     */
    public static String escape(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isEscapingChar(value.charAt(i))) {
                return doEscape(value.getBytes(StandardCharsets.UTF_8), i);
            }
        }
        return value;
    }

    private static boolean isEscapingChar(char c) {
        return c < ' ' || c >= '~' || c == '%';
    }

    private static boolean isEscapingChar(byte b) {
        return b < ' ' || b >= '~' || b == '%';
    }

    /**
     * Escapes the given byte array.
     *
     * @param valueBytes the UTF-8 bytes
     * @param ri The reader index, pointed at the first byte that needs escaping.
     */
    private static String doEscape(byte[] valueBytes, int ri) {
        final byte[] escapedBytes = TemporaryThreadLocals.get().byteArray(ri + (valueBytes.length - ri) * 3);
        // copy over the good bytes
        if (ri != 0) {
            System.arraycopy(valueBytes, 0, escapedBytes, 0, ri);
        }

        int wi = ri;
        for (; ri < valueBytes.length; ri++) {
            final byte b = valueBytes[ri];
            // Manually implement URL encoding, per the gRPC spec.
            if (isEscapingChar(b)) {
                escapedBytes[wi] = '%';
                escapedBytes[wi + 1] = HEX[(b >> 4) & 0xF];
                escapedBytes[wi + 2] = HEX[b & 0xF];
                wi += 3;
                continue;
            }
            escapedBytes[wi++] = b;
        }

        //noinspection deprecation
        return new String(escapedBytes, 0,  0, wi);
    }

    /**
     * Unescape the provided ascii to a unicode {@link String}.
     */
    public static String unescape(String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < ' ' || c >= '~' || (c == '%' && i + 2 < value.length())) {
                return doUnescape(value.getBytes(StandardCharsets.US_ASCII));
            }
        }
        return value;
    }

    private static String doUnescape(byte[] value) {
        final ByteBuffer buf = ByteBuffer.allocate(value.length);
        for (int i = 0; i < value.length;) {
            if (value[i] == '%' && i + 2 < value.length) {
                try {
                    buf.put((byte) Integer
                            .parseInt(new String(value, i + 1, 2, StandardCharsets.UTF_8), 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException e) {
                    // ignore, fall through, just push the bytes.
                }
            }
            buf.put(value[i]);
            i += 1;
        }
        return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
    }

    private StatusMessageEscaper() {}
}
