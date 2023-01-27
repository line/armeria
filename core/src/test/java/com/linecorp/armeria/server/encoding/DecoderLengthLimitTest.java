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

package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.encoding.StreamDecoder;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;

import io.netty.buffer.ByteBufAllocator;

class DecoderLengthLimitTest {

    @EnumSource(HttpEncodingType.class)
    @ParameterizedTest
    void decodedDataShouldNotExceedLengthLimit(HttpEncodingType encodingType) throws IOException {
        final String originalMessage = Strings.repeat("1", 10000);
        final ByteArrayOutputStream encodedStream = new ByteArrayOutputStream();
        final OutputStream encodingStream =
                HttpEncoders.getEncodingOutputStream(encodingType, encodedStream);
        encodingStream.write(originalMessage.getBytes());
        encodingStream.flush();
        final HttpData httpData = HttpData.wrap(encodedStream.toByteArray());
        final StreamDecoder lenientStreamDecoder = newStreamDecoder(encodingType, 10001);
        final HttpData decode0 = lenientStreamDecoder.decode(httpData);
        final HttpData decode1 = lenientStreamDecoder.finish();
        assertThat(decode0.toStringUtf8() + decode1.toStringUtf8()).isEqualTo(originalMessage);
        decode0.close();
        decode1.close();

        final StreamDecoder strictStreamDecoder = newStreamDecoder(encodingType, 9999);
        assertThatThrownBy(() -> {
            strictStreamDecoder.decode(httpData);
        }).isInstanceOf(ContentTooLargeException.class)
          .satisfies(cause -> {
              final ContentTooLargeException tooLargeException = (ContentTooLargeException) cause;
              assertThat(tooLargeException.maxContentLength()).isEqualTo(9999);
          });
    }

    @EnumSource(HttpEncodingType.class)
    @ParameterizedTest
    void chunkedDataShouldNotExceedLengthLimit(HttpEncodingType encodingType) throws IOException {
        // Use non-repeated texts to avoid high compression ratio and each chunk can have a complete text.
        final String originalMessage =
                IntStream.range(0, 5000)
                         .mapToObj(x -> String.valueOf(x))
                         .collect(Collectors.joining());
        final ByteArrayOutputStream encodedStream = new ByteArrayOutputStream();
        final OutputStream encodingStream =
                HttpEncoders.getEncodingOutputStream(encodingType, encodedStream);
        encodingStream.write(originalMessage.getBytes());
        encodingStream.flush();
        final byte[] compressed = encodedStream.toByteArray();
        final int middle = compressed.length / 2;
        final HttpData first = HttpData.copyOf(compressed, 0, middle);
        final HttpData second = HttpData.copyOf(compressed, middle, compressed.length - middle);

        final StreamDecoder lenientStreamDecoder = newStreamDecoder(encodingType, originalMessage.length());
        final HttpData decode0 = lenientStreamDecoder.decode(first);
        final HttpData decode1 = lenientStreamDecoder.decode(second);
        final HttpData decode2 = lenientStreamDecoder.finish();
        assertThat(decode0.toStringUtf8() + decode1.toStringUtf8() + decode2.toStringUtf8())
                .isEqualTo(originalMessage);
        decode0.close();
        decode1.close();
        decode2.close();

        final int maxLength = originalMessage.length() - 1;
        final StreamDecoder strictStreamDecoder = newStreamDecoder(encodingType, maxLength);
        strictStreamDecoder.decode(first).close();
        assertThatThrownBy(() -> {
            strictStreamDecoder.decode(second).close();
            strictStreamDecoder.finish().close();
        }).isInstanceOf(ContentTooLargeException.class)
          .satisfies(cause -> {
              final ContentTooLargeException tooLargeException = (ContentTooLargeException) cause;
              // Make sure the ContentTooLargeException was raised by the custom overflow checker.
              assertThat(tooLargeException.getCause()).isNull();
              assertThat(tooLargeException.maxContentLength()).isEqualTo(maxLength);
          });
    }

    private static StreamDecoder newStreamDecoder(HttpEncodingType encodingType, int maxLength) {
        switch (encodingType) {
            case GZIP:
                return StreamDecoderFactory.gzip().newDecoder(ByteBufAllocator.DEFAULT, maxLength);
            case DEFLATE:
                return StreamDecoderFactory.deflate().newDecoder(ByteBufAllocator.DEFAULT, maxLength);
            case BROTLI:
                return StreamDecoderFactory.brotli().newDecoder(ByteBufAllocator.DEFAULT, maxLength);
        }
        // Never reach here.
        throw new Error();
    }
}
