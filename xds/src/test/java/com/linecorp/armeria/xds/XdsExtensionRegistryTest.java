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

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;

import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.client.endpoint.ClusterTypeFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class XdsExtensionRegistryTest {

    private static final DirectoryWatchService watchService = new DirectoryWatchService();

    @AfterAll
    static void tearDown() {
        watchService.close();
    }

    @Test
    void spiFactoriesLoadedByDefault() {
        final XdsExtensionRegistry registry = createRegistry();
        assertThat(registry.queryByName("envoy.filters.http.router", HttpFilterFactory.class))
                .isNotNull();
    }

    @Test
    void queryByNameReturnsNull() {
        final XdsExtensionRegistry registry = createRegistry();
        assertThat(registry.queryByName("nonexistent.filter", HttpFilterFactory.class)).isNull();
    }

    @Test
    void assertValid() {
        final XdsExtensionRegistry registry = createRegistry();
        registry.assertValid(Duration.newBuilder().setSeconds(42).build());
    }

    @Test
    void unpack() {
        final XdsExtensionRegistry registry = createRegistry();
        final Duration original = Duration.newBuilder().setSeconds(42).build();
        assertThat(registry.unpack(Any.pack(original), Duration.class)).isEqualTo(original);
    }

    @Test
    void queryPreferTypeUrl() {
        final XdsExtensionRegistry registry = createRegistry();
        final String routerTypeUrl =
                "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router";
        final Any any = Any.newBuilder().setTypeUrl(routerTypeUrl).build();
        assertThat(registry.query(any, "envoy.filters.http.router", HttpFilterFactory.class))
                .isNotNull();

        final Any unknownAny = Any.newBuilder().setTypeUrl("unknown").build();
        assertThat(registry.query(unknownAny, "envoy.filters.http.router",
                                  HttpFilterFactory.class))
                .isNotNull();
        assertThat(registry.query(unknownAny, "unknown", HttpFilterFactory.class)).isNull();
    }

    @Test
    void queryDisambiguatesByName() {
        final String sharedTypeUrl = "type.googleapis.com/test.SharedConfig";
        final TestClusterTypeFactory factoryA = new TestClusterTypeFactory("factoryA", sharedTypeUrl);
        final TestClusterTypeFactory factoryB = new TestClusterTypeFactory("factoryB", sharedTypeUrl);
        final XdsExtensionRegistry registry = createRegistry(factoryA, factoryB);

        final Any any = Any.newBuilder().setTypeUrl(sharedTypeUrl).build();
        assertThat(registry.query(any, "factoryA", ClusterTypeFactory.class))
                .isSameAs(factoryA);
        assertThat(registry.query(any, "factoryB", ClusterTypeFactory.class))
                .isSameAs(factoryB);
    }

    @Test
    void duplicateNameWithinLevelThrows() {
        final String typeUrl = "type.googleapis.com/test.Config";
        final TestClusterTypeFactory first = new TestClusterTypeFactory("sameName", typeUrl);
        final TestClusterTypeFactory second = new TestClusterTypeFactory("sameName", typeUrl);
        assertThatThrownBy(() -> createRegistry(first, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate factory name")
                .hasMessageContaining("sameName");
    }

    @Test
    void crossLevelOverwrites() {
        final String builtInName = "armeria.cluster.static";
        final TestClusterTypeFactory override =
                new TestClusterTypeFactory(builtInName, "type.googleapis.com/test.Override");
        final XdsExtensionRegistry registry = createRegistry(override);

        final ClusterTypeFactory resolved = registry.queryByName(builtInName, ClusterTypeFactory.class);
        assertThat(resolved).isNotNull();
        assertThat(resolved).isNotSameAs(override);
    }

    @Test
    void disambiguateDoesNotFallThroughToByName() {
        final String sharedTypeUrl = "type.googleapis.com/test.SharedConfig";
        final TestClusterTypeFactory factoryA = new TestClusterTypeFactory("factoryA", sharedTypeUrl);
        final TestClusterTypeFactory factoryB = new TestClusterTypeFactory("factoryB", sharedTypeUrl);
        final TestClusterTypeFactory factoryC =
                new TestClusterTypeFactory("factoryC", "type.googleapis.com/test.OtherConfig");
        final XdsExtensionRegistry registry = createRegistry(factoryA, factoryB, factoryC);

        final Any sharedAny = Any.newBuilder().setTypeUrl(sharedTypeUrl).build();
        assertThat(registry.query(sharedAny, "factoryC", ClusterTypeFactory.class)).isNull();
    }

    private static XdsExtensionRegistry createRegistry() {
        return createRegistry(new XdsExtensionFactory[0]);
    }

    private static XdsExtensionRegistry createRegistry(XdsExtensionFactory... factories) {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        return XdsExtensionRegistry.of(new XdsResourceValidator(),
                                       watchService,
                                       meterRegistry,
                                       new MeterIdPrefix("test"),
                                       ImmutableList.copyOf(factories));
    }

    private static final class TestClusterTypeFactory implements ClusterTypeFactory {

        private final String name;
        private final List<String> typeUrls;

        TestClusterTypeFactory(String name, String typeUrl) {
            this.name = name;
            typeUrls = ImmutableList.of(typeUrl);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> typeUrls() {
            return typeUrls;
        }

        @Override
        public SnapshotStream<EndpointSnapshot> createEndpointStream(ClusterXdsResource clusterXdsResource,
                                                                     FactoryContext context) {
            throw new UnsupportedOperationException("Test-only factory");
        }
    }
}
