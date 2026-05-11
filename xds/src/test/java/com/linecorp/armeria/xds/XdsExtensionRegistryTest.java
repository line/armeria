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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;

import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class XdsExtensionRegistryTest {

    private static XdsExtensionRegistry createRegistry() {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        return XdsExtensionRegistry.of(new XdsResourceValidator(),
                                       watchService,
                                       meterRegistry,
                                       new MeterIdPrefix("test"));
    }

    private static final DirectoryWatchService watchService = new DirectoryWatchService();

    @AfterAll
    static void tearDown() {
        watchService.close();
    }

    @Test
    void queryWithTypeMismatch() {
        // HttpConnectionManagerFactory is registered by default and is not an HttpFilterFactory
        final XdsExtensionRegistry registry = createRegistry();
        assertThatThrownBy(() -> registry.queryByTypeUrl(
                "type.googleapis.com/envoy.extensions.filters.network" +
                ".http_connection_manager.v3.HttpConnectionManager",
                HttpFilterFactory.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected");
    }

    @Test
    void spiFactoriesLoadedByDefault() {
        // SPI should load RouterFilterFactory
        final XdsExtensionRegistry registry = createRegistry();
        final HttpFilterFactory resolved = registry.queryByName(
                "envoy.filters.http.router", HttpFilterFactory.class);
        assertThat(resolved).isNotNull();
        assertThat(resolved).isInstanceOf(HttpFilterFactory.class);
    }

    @Test
    void emptyRegistryReturnsNull() {
        final XdsExtensionRegistry registry = createRegistry();
        assertThat(registry.queryByName("nonexistent.filter", HttpFilterFactory.class)).isNull();
        assertThat(registry.queryByTypeUrl("type.googleapis.com/nonexistent",
                                            HttpFilterFactory.class)).isNull();
    }

    @Test
    void assertValidDelegatesToValidator() {
        final XdsExtensionRegistry registry = createRegistry();
        // Should not throw for a valid message
        final Duration valid = Duration.newBuilder().setSeconds(42).build();
        registry.assertValid(valid);
    }

    @Test
    void unpackDelegatesToValidator() {
        final XdsExtensionRegistry registry = createRegistry();
        final Duration original = Duration.newBuilder().setSeconds(42).build();
        final Any packed = Any.pack(original);
        final Duration unpacked = registry.unpack(packed, Duration.class);
        assertThat(unpacked).isEqualTo(original);
    }

    @Test
    void queryPreferTypeUrl() {
        final XdsExtensionRegistry registry = createRegistry();
        // RouterFilterFactory is registered by both name and type URL via SPI
        final String routerTypeUrl =
                "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router";
        final Any any = Any.newBuilder().setTypeUrl(routerTypeUrl).build();

        // query() checks type URL first
        assertThat(registry.query(any, "envoy.filters.http.router", HttpFilterFactory.class))
                .isNotNull();
        // falls back to name when type URL doesn't match
        final Any unknownAny = Any.newBuilder().setTypeUrl("unknown").build();
        assertThat(registry.query(unknownAny, "envoy.filters.http.router",
                                  HttpFilterFactory.class))
                .isNotNull();
        // returns null when neither matches
        assertThat(registry.query(unknownAny, "unknown", HttpFilterFactory.class)).isNull();
    }
}
