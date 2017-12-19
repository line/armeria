/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class TextFormatterTest {
    @Test
    public void size() throws Exception {
        assertThat(TextFormatter.size(100).toString()).isEqualTo("100B");
        assertThat(TextFormatter.size(100 * 1024 + 1).toString()).isEqualTo("100KiB(102401B)");
        assertThat(TextFormatter.size(100 * 1024 * 1024 + 1).toString()).isEqualTo("100MiB(104857601B)");
    }

    @Test
    public void elapsed() throws Exception {
        assertThat(TextFormatter.elapsed(1, 100).toString()).isEqualTo("99ns");
        assertThat(TextFormatter.elapsed(TimeUnit.MICROSECONDS.toNanos(100) + 1).toString())
                .isEqualTo("100\u00B5s(100001ns)"); // microsecs
        assertThat(TextFormatter.elapsed(TimeUnit.MILLISECONDS.toNanos(100) + 1).toString())
                .isEqualTo("100ms(100000001ns)");
        assertThat(TextFormatter.elapsed(TimeUnit.SECONDS.toNanos(100) + 1).toString())
                .isEqualTo("100s(100000000001ns)");
    }

    @Test
    public void elapsedAndSize() throws Exception {
        assertThat(TextFormatter.elapsedAndSize(1, 100, 1024 * 100).toString())
                .isEqualTo("99ns, 100KiB(102400B)");
    }

    @Test
    public void testFormatEpoch() throws Exception {
        assertThat(TextFormatter.epoch(1478601399123L).toString())
                .isEqualTo("2016-11-08T10:36:39.123Z(1478601399123)");
    }
}
