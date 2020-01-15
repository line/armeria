/*
 * Copyright 2020 LINE Corporation
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

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void getExists() {
        assertThat(Version.get("armeria").artifactVersion()).isNotEqualTo("unknown");
    }

    @Test
    void getExists_classLoader() {
        assertThat(Version.get("armeria", VersionTest.class.getClassLoader()).artifactVersion())
                .isNotEqualTo("unknown");
    }

    @Test
    void getNotExists() {
        assertThat(Version.get("finagle").artifactVersion()).isEqualTo("unknown");
    }

    @Test
    void getNotExists_classLoader() {
        assertThat(Version.get("finagle", VersionTest.class.getClassLoader()).artifactVersion())
                .isEqualTo("unknown");
    }
}
