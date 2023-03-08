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
import java.util.Set;

import org.assertj.core.util.Sets;
import org.junit.jupiter.api.Test;

class ThriftMetadataAccessTest {

    @Test
    void emptyCase() {
        // can't determine the thrift version used so just pre-initialize
        assertThat(ThriftMetadataAccess.needsPreInitialization(Collections.emptySet())).isTrue();
    }

    @Test
    void basicCase() {
        assertThat(ThriftMetadataAccess.needsPreInitialization(
                Collections.singleton("armeria-thrift0.13"))).isTrue();
        assertThat(ThriftMetadataAccess.needsPreInitialization(
                Collections.singleton("armeria-thrift0.15"))).isFalse();

        assertThat(ThriftMetadataAccess.needsPreInitialization(
                Sets.set("armeria-thrift0.13", "armeria-thrift0.14", "armeria-thrift0.15"))).isTrue();
        assertThat(ThriftMetadataAccess.needsPreInitialization(
                Sets.set("armeria-thrift0.15", "armeria-thrift0.16", "armeria-thrift0.17"))).isFalse();
    }

    @Test
    void withMalformed() {
        Set<String> mixed = Sets.set("armeria-thrift0.13", "asdf", "armeria-thrif");
        assertThat(ThriftMetadataAccess.needsPreInitialization(mixed)).isTrue();

        mixed = Sets.set("armeria-thrift0.15", "asdf", "armeria-thrif");
        assertThat(ThriftMetadataAccess.needsPreInitialization(mixed)).isFalse();

        mixed = Sets.set("asdf", "armeria-thrif");
        assertThat(ThriftMetadataAccess.needsPreInitialization(mixed)).isFalse();
    }
}
