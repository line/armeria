/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class SystemInfoTest {

    @Test
    public void javaVersion() {
        assertThat(SystemInfo.javaVersion()).isGreaterThanOrEqualTo(8);
    }

    @Test
    public void hostname() {
        assertThat(SystemInfo.hostname()).isNotBlank().isLowerCase();
    }

    @Test
    public void pid() {
        assertThat(SystemInfo.pid()).isPositive();
    }

    @Test
    public void currentTimeMicros() {
        final long expected = System.currentTimeMillis() * 1000L;
        assertThat(SystemInfo.currentTimeMicros()).isBetween(expected - 1_000_000L, expected + 1_000_000L);
    }
}
