/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Properties;

import org.assertj.core.util.Lists;
import org.assertj.core.util.Sets;
import org.junit.jupiter.api.Test;

class ThriftMetadataAccessTest {

    @Test
    void wrongCountCase() {
        // empty properties
        assertThat(ThriftMetadataAccess.needsPreInitialization(Collections.emptyList())).isTrue();

        // more than one properties
        assertThat(ThriftMetadataAccess.needsPreInitialization(
                Lists.list(new Properties(), new Properties()))).isTrue();
    }

    @Test
    void basicCase() {
        final Properties props = new Properties();
        props.put("structPreinitRequired", "true");
        assertThat(ThriftMetadataAccess.needsPreInitialization(Collections.singletonList(props))).isTrue();
    }

    @Test
    void failingCase() {
        final Properties props = new Properties();
        assertThat(ThriftMetadataAccess.needsPreInitialization(Collections.singletonList(props))).isFalse();

        props.put("structPreinitRequired", "false");
        assertThat(ThriftMetadataAccess.needsPreInitialization(Collections.singletonList(props))).isFalse();

        props.put("structPreinitRequired", "asdf");
        assertThat(ThriftMetadataAccess.needsPreInitialization(Collections.singletonList(props))).isFalse();
    }
}
