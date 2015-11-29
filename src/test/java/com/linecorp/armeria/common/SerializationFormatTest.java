package com.linecorp.armeria.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class SerializationFormatTest {

    @Test
    public void fromMimeType_exactMatch() {
        for (SerializationFormat format : SerializationFormat.values()) {
            assertSame(format, SerializationFormat.fromMimeType(format.mimeType()).get());
        }
    }

    @Test
    public void fromMimeType_normalizes() {
        assertSame(SerializationFormat.THRIFT_BINARY,
                   SerializationFormat.fromMimeType("application/x-thrift; protocol=tbinary").get());
        assertSame(SerializationFormat.THRIFT_COMPACT,
                   SerializationFormat.fromMimeType("application/x-thrift;protocol=TCompact").get());
        assertSame(SerializationFormat.THRIFT_JSON,
                   SerializationFormat.fromMimeType("application/x-thrift ; protocol=\"TjSoN\"").get());
        assertSame(SerializationFormat.THRIFT_TEXT,
                   SerializationFormat.fromMimeType("application/x-thrift ; version=3;protocol=ttext").get());
    }

    @Test
    public void fromMimeType_notRecognized() {
        assertFalse(SerializationFormat.fromMimeType("foo/bar").isPresent());
        assertFalse(SerializationFormat.fromMimeType(null).isPresent());
    }
}
