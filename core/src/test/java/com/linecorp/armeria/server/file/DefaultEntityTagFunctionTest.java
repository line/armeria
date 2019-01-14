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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class DefaultEntityTagFunctionTest {
    @Test
    public void test() {
        // Make sure all-zero input produces a non-empty tag.
        final DefaultEntityTagFunction f = DefaultEntityTagFunction.get();
        assertThat(f.apply("", new HttpFileAttributes(0, 0))).isEqualTo("-");

        // Make sure non-leading zeros are not stripped.
        assertThat(f.apply("", new HttpFileAttributes(0x0001000000000000L, 0x0000000000010000L)))
                .isEqualTo("AQAAAAAAAAEAAA"); // = 01 00 00 00 00 00 00 / 01 00 00

        // Test a realistic one.
        assertThat(f.apply("/favicon.ico", new HttpFileAttributes(892, 1546933942837L)))
                .isEqualTo("eA50LAN8AWgscro1");
    }
}
