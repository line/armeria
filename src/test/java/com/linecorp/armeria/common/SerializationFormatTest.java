package com.linecorp.armeria.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class SerializationFormatTest {

    @Test
    public void fromMimeType_exactMatch() {
        for (SerializationFormat format : SerializationFormat.values()) {
            if (format == SerializationFormat.UNKNOWN) {
                continue;
            }
            assertSame(format, SerializationFormat.fromMediaType(format.mediaType().toString()).get());
        }
    }

    @Test
    public void fromMimeType_normalizes() {
        assertSame(SerializationFormat.THRIFT_BINARY,
                   SerializationFormat.fromMediaType("application/x-thrift; protocol=tbinary").get());
        assertSame(SerializationFormat.THRIFT_COMPACT,
                   SerializationFormat.fromMediaType("application/x-thrift;protocol=TCompact").get());
        assertSame(SerializationFormat.THRIFT_JSON,
                   SerializationFormat.fromMediaType("application/x-thrift ; protocol=\"TjSoN\"").get());
        assertSame(SerializationFormat.THRIFT_TEXT,
                   SerializationFormat.fromMediaType("application/x-thrift ; version=3;protocol=ttext").get());
    }

    @Test
    public void fromMimeType_notRecognized() {
        assertFalse(SerializationFormat.fromMediaType("foo/bar").isPresent());
        assertFalse(SerializationFormat.fromMediaType(null).isPresent());
    }
}
