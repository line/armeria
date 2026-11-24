/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.io.ByteStreams;

class SizeEnforcingInputStreamTest {

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 8192 })
    void acceptsExactlyTheLimit(int length) throws Exception {
        try (SizeEnforcingInputStream stream = newStream(length, length)) {
            assertThat(ByteStreams.toByteArray(stream)).hasSize(length);
        }
    }

    @Test
    void rejectsSingleByteReadOverTheLimit() throws Exception {
        try (SizeEnforcingInputStream stream = newStream(4, 3)) {
            assertThat(stream.read()).isZero();
            assertThat(stream.read()).isZero();
            assertThat(stream.read()).isZero();
            assertThatThrownBy(stream::read)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("bytes read: 4");
        }
    }

    @Test
    void rejectsBulkReadOverTheLimit() throws Exception {
        try (SizeEnforcingInputStream stream = newStream(4, 3)) {
            assertThat(stream.read(new byte[3])).isEqualTo(3);
            assertThatThrownBy(() -> stream.read(new byte[3]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("bytes read: 4");
        }
    }

    @Test
    void rejectsSkipOverTheLimit() throws Exception {
        try (SizeEnforcingInputStream stream = newStream(4, 3)) {
            assertThatThrownBy(() -> stream.skip(4))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("bytes read: 4");
        }
    }

    @Test
    void skippedBytesCountTowardsTheLimit() throws Exception {
        try (SizeEnforcingInputStream stream = newStream(4, 3)) {
            assertThat(stream.skip(2)).isEqualTo(2);
            assertThat(stream.read()).isZero();
            assertThatThrownBy(stream::read)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("bytes read: 4");
        }
    }

    @Test
    void resetRestoresTheCount() throws Exception {
        try (SizeEnforcingInputStream stream = newStream(4, 4)) {
            assertThat(stream.read()).isZero();
            stream.mark(3);
            assertThat(ByteStreams.toByteArray(stream)).hasSize(3);
            stream.reset();
            assertThat(ByteStreams.toByteArray(stream)).hasSize(3);
        }
    }

    @Test
    void resetRequiresAMark() throws Exception {
        try (SizeEnforcingInputStream stream = newStream(1, 1)) {
            assertThatThrownBy(stream::reset)
                    .isInstanceOf(IOException.class)
                    .hasMessage("Mark not set");
        }
    }

    private static SizeEnforcingInputStream newStream(int length, int maxLength) {
        return new SizeEnforcingInputStream(new ByteArrayInputStream(new byte[length]), maxLength,
                                            count -> new IllegalStateException("bytes read: " + count));
    }
}
