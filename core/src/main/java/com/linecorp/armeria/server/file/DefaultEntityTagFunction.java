package com.linecorp.armeria.server.file;

import java.util.function.BiFunction;

import com.google.common.io.BaseEncoding;

final class DefaultEntityTagFunction implements BiFunction<String, HttpFileAttributes, String> {

    private static final BaseEncoding etagEncoding = BaseEncoding.base64().omitPadding();

    private static final DefaultEntityTagFunction INSTANCE = new DefaultEntityTagFunction();

    static DefaultEntityTagFunction get() {
        return INSTANCE;
    }

    private DefaultEntityTagFunction() {}

    @Override
    public String apply(String pathOrUri, HttpFileAttributes attrs) {
        final byte[] data = new byte[4 + 8 + 8];
        final long hashCode = pathOrUri.hashCode() & 0xFFFFFFFFL;
        final long length = attrs.length();
        final long lastModifiedMillis = attrs.lastModifiedMillis();

        int offset = 0;
        offset = appendInt(data, offset, hashCode);
        offset = appendLong(data, offset, length);
        offset = appendLong(data, offset, lastModifiedMillis);

        return offset != 0 ? etagEncoding.encode(data, 0, offset) : "-";
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
