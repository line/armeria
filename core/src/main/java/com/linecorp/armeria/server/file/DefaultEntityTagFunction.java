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
package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

final class DefaultEntityTagFunction implements BiFunction<String, HttpFileAttributes, String> {

    private static final BaseEncoding etagEncoding = BaseEncoding.base64().omitPadding();

    private static final DefaultEntityTagFunction INSTANCE = new DefaultEntityTagFunction();

    static DefaultEntityTagFunction get() {
        return INSTANCE;
    }

    private DefaultEntityTagFunction() {}

    @Override
    public String apply(String pathOrUri, HttpFileAttributes attrs) {
        requireNonNull(pathOrUri, "pathOrUri");
        requireNonNull(attrs, "attrs");

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final byte[] data = tempThreadLocals.byteArray(4 + 8 + 8);
            final long hashCode = pathOrUri.hashCode() & 0xFFFFFFFFL;
            final long length = attrs.length();
            final long lastModifiedMillis = attrs.lastModifiedMillis();

            int offset = 0;
            offset = appendInt(data, offset, hashCode);
            offset = appendLong(data, offset, length);
            offset = appendLong(data, offset, lastModifiedMillis);

            return offset != 0 ? etagEncoding.encode(data, 0, offset) : "-";
        }
    }

    /**
     * Appends a 64-bit integer without its leading zero bytes.
     */
    private static int appendLong(byte[] data, int offset, long value) {
        offset = appendByte(data, offset, value >>> 56);
        offset = appendByte(data, offset, value >>> 48);
        offset = appendByte(data, offset, value >>> 40);
        offset = appendByte(data, offset, value >>> 32);
        offset = appendInt(data, offset, value);
        return offset;
    }

    /**
     * Appends a 32-bit integer without its leading zero bytes.
     */
    private static int appendInt(byte[] data, int offset, long value) {
        offset = appendByte(data, offset, value >>> 24);
        offset = appendByte(data, offset, value >>> 16);
        offset = appendByte(data, offset, value >>> 8);
        offset = appendByte(data, offset, value);
        return offset;
    }

    /**
     * Appends a byte if it's not a leading zero.
     */
    private static int appendByte(byte[] dst, int offset, long value) {
        if (value == 0) {
            return offset;
        }
        dst[offset] = (byte) value;
        return offset + 1;
    }
}
