package com.linecorp.armeria.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class SerializationFormatTest {

    @Test
    public void fromMimeType_exactMatch() {
        for (SerializationFormat format : SerializationFormat.values()) {
            assertEquals(format, SerializationFormat.fromMimeType(format.mimeType()).get());
        }
    }

    @Test
    public void fromMimeType_normalizes() {
        assertEquals(SerializationFormat.THRIFT_BINARY,
                     SerializationFormat.fromMimeType("application/x-thrift; protocol=tbinary").get());
        assertEquals(SerializationFormat.THRIFT_COMPACT,
                     SerializationFormat.fromMimeType("application/x-thrift;protocol=TCompact").get());
    }

    @Test
    public void fromMimeType_notRecognized() {
        assertFalse(SerializationFormat.fromMimeType("foo/bar").isPresent());
        assertFalse(SerializationFormat.fromMimeType(null).isPresent());
    }
}
