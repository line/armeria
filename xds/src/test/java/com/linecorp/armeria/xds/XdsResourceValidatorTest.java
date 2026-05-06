/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;

import com.linecorp.armeria.xds.validator.XdsValidationException;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.pgv.ValidationException;

class XdsResourceValidatorTest {

    @Test
    void pgvValidationIsEnabled() {
        final XdsResourceValidator validator = new XdsResourceValidator();
        final VirtualHost virtualHost = VirtualHost.getDefaultInstance();
        assertThatThrownBy(() -> validator.assertValid(virtualHost))
                .isInstanceOf(XdsValidationException.class)
                .cause()
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("length must be at least 1 but got: 0");
    }

    @Test
    void supportedFieldValidationRuns() {
        // SPI loads DefaultXdsValidatorIndex which includes supported-field validation
        final XdsResourceValidator validator = new XdsResourceValidator();
        // Cluster with name set (passes pgv) — should not throw
        final Cluster cluster = Cluster.newBuilder().setName("test").build();
        validator.assertValid(cluster);
    }

    @Test
    void unpackValidatesUnpackedMessage() {
        final XdsResourceValidator validator = new XdsResourceValidator();
        // pack a valid cluster
        final Cluster cluster = Cluster.newBuilder().setName("test").build();
        final Any packed = Any.pack(cluster);
        final Cluster unpacked = validator.unpack(packed, Cluster.class);
        assertThat(unpacked.getName()).isEqualTo("test");
    }

    @Test
    void unpackFailsOnInvalidMessage() {
        final XdsResourceValidator validator = new XdsResourceValidator();
        // Pack a default VirtualHost (will fail pgv)
        final VirtualHost vhost = VirtualHost.getDefaultInstance();
        final Any packed = Any.pack(vhost);
        assertThatThrownBy(() -> validator.unpack(packed, VirtualHost.class))
                .isInstanceOf(XdsValidationException.class);
    }
}
