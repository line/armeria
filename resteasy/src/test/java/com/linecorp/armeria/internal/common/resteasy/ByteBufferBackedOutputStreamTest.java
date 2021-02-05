/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common.resteasy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class ByteBufferBackedOutputStreamTest {

    @Test
    void testWriteBytes() throws Exception {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        final ByteBufferBackedOutputStream stream = new ByteBufferBackedOutputStream(3, buffer -> {
            final CharSequence token =
                    StandardCharsets.ISO_8859_1.decode(buffer);
            tokens.add(token.toString());
            sb.append(token);
        });

        final String s1 = "aaabbbcccdddeeefffggghhh9";
        stream.write(s1.getBytes(StandardCharsets.ISO_8859_1));

        assertThat(stream.hasWritten()).isTrue();
        assertThat(stream.written()).isEqualTo(1);
        assertThat(stream.hasFlushed()).isTrue();

        assertThat(tokens).hasSize(8);
        assertThat(tokens).containsExactly("aaa", "bbb", "ccc", "ddd", "eee", "fff", "ggg", "hhh");

        stream.flush();
        assertThat(tokens).hasSize(9);
        assertThat(tokens).last().isEqualTo("9");

        assertThat(sb.toString()).isEqualTo(s1);
    }

    @Test
    void testWriteByte() throws Exception {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        final ByteBufferBackedOutputStream stream = new ByteBufferBackedOutputStream(3, buffer -> {
            final CharSequence token =
                    StandardCharsets.ISO_8859_1.decode(buffer);
            tokens.add(token.toString());
            sb.append(token);
        });

        final String s1 = "aaabbbcccdddeeefffggghhh9";
        for (byte b : s1.getBytes(StandardCharsets.ISO_8859_1)) {
            stream.write(b);
        }

        assertThat(stream.hasWritten()).isTrue();
        assertThat(stream.written()).isEqualTo(1);
        assertThat(stream.hasFlushed()).isTrue();

        assertThat(tokens).hasSize(8);
        assertThat(tokens).containsExactly("aaa", "bbb", "ccc", "ddd", "eee", "fff", "ggg", "hhh");

        stream.flush();
        assertThat(tokens).hasSize(9);
        assertThat(tokens).last().isEqualTo("9");

        assertThat(sb.toString()).isEqualTo(s1);
    }

    @Test
    void testWriteRead() throws Exception {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        final ByteBufferBackedOutputStream stream = new ByteBufferBackedOutputStream(3, buffer -> {
            final CharSequence token =
                    StandardCharsets.ISO_8859_1.decode(buffer);
            tokens.add(token.toString());
            sb.append(token);
        });

        final String s1 = "aaabbbcccdddeeefffggghhh9";
        stream.write(s1.getBytes(StandardCharsets.ISO_8859_1));

        assertThat(stream.hasWritten()).isTrue();
        assertThat(stream.written()).isEqualTo(1);
        assertThat(stream.hasFlushed()).isTrue();

        assertThat(tokens).hasSize(8);
        assertThat(tokens).containsExactly("aaa", "bbb", "ccc", "ddd", "eee", "fff", "ggg", "hhh");

        stream.flush();
        assertThat(tokens).hasSize(9);
        assertThat(tokens).last().isEqualTo("9");

        assertThat(sb.toString()).isEqualTo(s1);

        stream.reset();
        assertThat(stream.hasWritten()).isFalse();
        assertThat(stream.written()).isEqualTo(0);
        assertThat(stream.hasFlushed()).isFalse();
        tokens.clear();
        sb.delete(0, sb.length());

        final String s2 = "iiijjjkkklllmmmnnn7";
        stream.write(s2.getBytes(StandardCharsets.ISO_8859_1));

        assertThat(stream.hasWritten()).isTrue();
        assertThat(stream.written()).isEqualTo(1);
        assertThat(stream.hasFlushed()).isTrue();

        assertThat(tokens).hasSize(6);
        assertThat(tokens).containsExactly("iii", "jjj", "kkk", "lll", "mmm", "nnn");

        final ByteBuffer dump = stream.dumpWrittenAndClose();
        assertThat(StandardCharsets.ISO_8859_1.decode(dump).toString())
                .isEqualTo("7");

        assertThat(sb.toString()).isEqualTo(s2.substring(0, s2.length() - 1));
    }
}
