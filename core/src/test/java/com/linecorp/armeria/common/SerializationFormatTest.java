package com.linecorp.armeria.common;

import static com.google.common.net.MediaType.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class SerializationFormatTest {
    @Test
    public void findByMediaType_exactMatch() {
        for (SerializationFormat format : SerializationFormat.values()) {
            if (format == SerializationFormat.UNKNOWN) {
                continue;
            }
            assertThat(SerializationFormat.find(format.mediaType()).get()).isSameAs(format);
        }
    }

    @Test
    public void findByMediaType_notRecognized() {
        assertThat(SerializationFormat.find(parse("foo/bar"))).isEmpty();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void nullThriftSerializationFormats() {
        assertThat(SerializationFormat.THRIFT_BINARY).isNull();
        assertThat(SerializationFormat.THRIFT_COMPACT).isNull();
        assertThat(SerializationFormat.THRIFT_JSON).isNull();
        assertThat(SerializationFormat.THRIFT_TEXT).isNull();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void failingOfThrift() {
        assertThatThrownBy(SerializationFormat::ofThrift).isInstanceOf(IllegalStateException.class);
    }
}
