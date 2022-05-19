/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.HttpData;

import reactor.test.StepVerifier;

class StreamWriterOutputStreamTest {

    @Test
    void write() throws IOException {
        final DefaultStreamMessage<String> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(HttpData::toStringUtf8);

        for (byte b : "foo".getBytes()) {
            outputStream.write(b);
        }
        outputStream.close();

        StepVerifier.create(writer)
                    .expectNext("foo")
                    .verifyComplete();
    }

    @Test
    void writeStrings() throws IOException {
        final DefaultStreamMessage<String> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(HttpData::toStringUtf8);

        final List<String> strings = ImmutableList.of("foo", "bar", "baz");
        for (String string : strings) {
            for (byte b : string.getBytes()) {
                outputStream.write(b);
            }
            outputStream.flush();
        }
        outputStream.close();

        StepVerifier.create(writer)
                    .expectNext("foo", "bar", "baz")
                    .verifyComplete();
    }

    @Test
    void writeIntegers() throws IOException {
        final DefaultStreamMessage<Integer> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(x -> Integer.valueOf(x.byteBuf().readByte()));

        final List<Integer> integers = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (Integer integer : integers) {
            outputStream.write(integer.byteValue());
            outputStream.flush();
        }
        outputStream.close();

        StepVerifier.create(writer)
                    .expectNext(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                    .verifyComplete();
    }

    @Test
    void writeWithStreamMessage() throws IOException {
        final StreamMessage<Integer> source = StreamMessage
                .of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .filter(x -> x % 2 == 0)
                .map(x -> x + 10); // 12, 14, 16, 18, 20
        final DefaultStreamMessage<Integer> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(x -> Integer.valueOf(x.byteBuf().readByte()));
        final StreamMessage<Integer> concat = StreamMessage.concat(source, writer);

        final List<Integer> integers = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (Integer integer : integers) {
            outputStream.write(integer.byteValue());
            outputStream.flush();
        }
        outputStream.close();

        StepVerifier.create(concat)
                    .expectNext(12, 14, 16, 18, 20, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                    .verifyComplete();
    }

    @Test
    void writeWithOffset() throws IOException {
        final DefaultStreamMessage<String> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(HttpData::toStringUtf8);
        final List<String> strings = ImmutableList.of("foo", "bar", "baz");
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(0);
        byteArrayOutputStream.write(strings.stream()
                                           .map(String::getBytes)
                                           .reduce(Bytes::concat).get());
        byteArrayOutputStream.write(0);

        outputStream.write(byteArrayOutputStream.toByteArray(), 1, byteArrayOutputStream.size() - 2);
        outputStream.close();

        StepVerifier.create(writer)
                    .expectNext("foobarbaz")
                    .verifyComplete();
    }

    @Test
    void write_exceedMaxBufferSize() throws IOException {
        final DefaultStreamMessage<String> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(HttpData::toStringUtf8, 3);

        for (byte b : "foobarbaz".getBytes()) {
            outputStream.write(b);
        }
        outputStream.close();

        StepVerifier.create(writer)
                    .expectNext("foo", "bar", "baz")
                    .verifyComplete();
    }

    @Test
    void writeWithOffset_exceedMaxBufferSize() throws IOException {
        final DefaultStreamMessage<String> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(HttpData::toStringUtf8, 3);
        final List<String> strings = ImmutableList.of("3", "456", "789", "ABC", "D");
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(0);
        byteArrayOutputStream.write(strings.stream()
                                           .map(String::getBytes)
                                           .reduce(Bytes::concat).get());
        byteArrayOutputStream.write(0);

        for (byte b : "12".getBytes()) {
            outputStream.write(b);
        }
        outputStream.write(byteArrayOutputStream.toByteArray(), 1, byteArrayOutputStream.size() - 2);
        outputStream.close();

        StepVerifier.create(writer)
                    .expectNext("123", "456", "789", "ABC", "D")
                    .verifyComplete();
    }

    @ValueSource(ints = { Integer.MIN_VALUE, -1, 0 })
    @ParameterizedTest
    void write_nonPositiveMaxBufferSize(int maxBufferSize) throws IOException {
        final DefaultStreamMessage<String> writer = new DefaultStreamMessage<>();
        assertThatThrownBy(() -> writer.toOutputStream(HttpData::toStringUtf8, maxBufferSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxBufferSize should be positive");
    }

    @Test
    void close() throws IOException {
        final DefaultStreamMessage<Integer> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(x -> Integer.valueOf(x.byteBuf().readByte()));

        final List<Integer> integers = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (Integer integer : integers) {
            outputStream.write(integer.byteValue());
            outputStream.flush();
        }

        assertThatCode(outputStream::close).doesNotThrowAnyException();
        StepVerifier.create(writer)
                    .expectNext(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                    .verifyComplete();
        assertThatThrownBy(() -> outputStream.write(0))
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
        assertThatThrownBy(() -> outputStream.write(new byte[] { 0 }, 0, 1))
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
        assertThatThrownBy(outputStream::flush)
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
    }

    @Test
    void closeWriter() throws IOException {
        final DefaultStreamMessage<Integer> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(x -> Integer.valueOf(x.byteBuf().readByte()));

        final List<Integer> integers = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (Integer integer : integers) {
            outputStream.write(integer.byteValue());
            outputStream.flush();
        }

        outputStream.write(11);
        outputStream.flush();
        writer.close();

        StepVerifier.create(writer)
                    .expectNext(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
                    .verifyComplete();
        assertThatThrownBy(() -> outputStream.write(0))
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
        assertThatThrownBy(() -> outputStream.write(new byte[] { 0 }, 0, 1))
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
        assertThatThrownBy(outputStream::flush)
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
        assertThatCode(outputStream::close).doesNotThrowAnyException();
    }

    @Test
    void closeBeforeWrite() throws IOException {
        final DefaultStreamMessage<Integer> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(x -> Integer.valueOf(x.byteBuf().readByte()));

        assertThatCode(outputStream::close).doesNotThrowAnyException();
        StepVerifier.create(writer)
                    .verifyComplete();
        assertThatThrownBy(() -> outputStream.write(0))
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
        assertThatThrownBy(() -> outputStream.write(new byte[] { 0 }, 0, 1))
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
        assertThatThrownBy(outputStream::flush)
                .isInstanceOf(IOException.class)
                .hasMessage("Stream closed");
    }

    @Test
    void closeMultipleTimes() throws IOException {
        final DefaultStreamMessage<Integer> writer = new DefaultStreamMessage<>();
        final OutputStream outputStream = writer.toOutputStream(x -> Integer.valueOf(x.byteBuf().readByte()));

        final List<Integer> integers = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (Integer integer : integers) {
            outputStream.write(integer.byteValue());
            outputStream.flush();
        }
        assertThatCode(outputStream::close).doesNotThrowAnyException();
        StepVerifier.create(writer)
                    .expectNext(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                    .verifyComplete();

        for (int i = 0; i < 10; i++) {
            assertThatCode(outputStream::close).doesNotThrowAnyException();
            assertThatThrownBy(() -> outputStream.write(0))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Stream closed");
            assertThatThrownBy(() -> outputStream.write(new byte[] { 0 }, 0, 1))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Stream closed");
            assertThatThrownBy(outputStream::flush)
                    .isInstanceOf(IOException.class)
                    .hasMessage("Stream closed");
        }
    }
}
