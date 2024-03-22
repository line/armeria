/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.XdsTestResources.createStaticCluster;
import static com.linecorp.armeria.xds.XdsTestResources.endpoint;
import static com.linecorp.armeria.xds.XdsTestResources.localityLbEndpoints;
import static com.linecorp.armeria.xds.XdsTestResources.stringValue;
import static com.linecorp.armeria.xds.client.endpoint.EndpointTestUtil.fallbackListValue;
import static com.linecorp.armeria.xds.client.endpoint.EndpointTestUtil.lbSubsetConfig;
import static com.linecorp.armeria.xds.client.endpoint.EndpointTestUtil.lbSubsetSelector;
import static com.linecorp.armeria.xds.client.endpoint.EndpointTestUtil.metadata;
import static com.linecorp.armeria.xds.client.endpoint.EndpointTestUtil.staticResourceListener;
import static com.linecorp.armeria.xds.client.endpoint.EndpointTestUtil.struct;
import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.ENVOY_LB_FALLBACK_LIST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsTestResources;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetMetadataFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class SubsetTest {

    private static final Value LIST_VALUE =
            Value.newBuilder()
                 .setListValue(ListValue.newBuilder()
                                        .addValues(stringValue("val1"))
                                        .addValues(stringValue("val2"))
                                        .addValues(stringValue("val3"))
                 )
                 .build();

    @Test
    void basicCase() {
        final Listener listener =
                staticResourceListener(metadata(ImmutableMap.of("key1", "val1",
                                                                "key2", "val2")));

        // struct with different orders still pass the equality test
        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080,
                                          metadata(ImmutableMap.of("key2", "val2",
                                                                   "key1", "val1"))),
                                 endpoint("127.0.0.1", 8081));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key2", "key1")));
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
        }
    }

    @Test
    void fallbackKeysSubset() {
        final Listener listener =
                staticResourceListener(metadata(ImmutableMap.of("key1", "val1",
                                                                "key2", "val2")));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080, metadata(ImmutableMap.of("key1", "val1"))),
                                 endpoint("127.0.0.1", 8081, metadata(ImmutableMap.of("key2", "val2"))));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key1", "key2"))
                                       .toBuilder()
                                       .addFallbackKeysSubset("key2")
                                       .setFallbackPolicy(LbSubsetSelectorFallbackPolicy.KEYS_SUBSET)
                                       .build(),
                               lbSubsetSelector(ImmutableList.of("key2"))
                                       .toBuilder()
                                       .setFallbackPolicy(LbSubsetSelectorFallbackPolicy.NO_FALLBACK)
                                       .build());
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
        }
    }

    private static Stream<Arguments> fallbackKeysDefaultArgs() {
        return Stream.of(Arguments.of(null,
                                      Endpoint.of("127.0.0.1", 8080),
                                      Endpoint.of("127.0.0.1", 8081)),
                         Arguments.of(Struct.getDefaultInstance(),
                                      Endpoint.of("127.0.0.1", 8080),
                                      Endpoint.of("127.0.0.1", 8081)),
                         Arguments.of(struct(ImmutableMap.of("key0", "val0")),
                                      Endpoint.of("127.0.0.1", 8080),
                                      Endpoint.of("127.0.0.1", 8081)),
                         // the default subset matches the first endpoint only
                         Arguments.of(struct(ImmutableMap.of("key1", "val1")),
                                      Endpoint.of("127.0.0.1", 8080),
                                      Endpoint.of("127.0.0.1", 8080)),
                         // the default subset doesn't match any endpoints
                         Arguments.of(struct(ImmutableMap.of("key3", "val3")), null, null));
    }

    @ParameterizedTest
    @MethodSource("fallbackKeysDefaultArgs")
    void fallbackDefault(@Nullable Struct defaultSubset, @Nullable Endpoint endpoint1,
                         @Nullable Endpoint endpoint2) {
        final Listener listener =
                staticResourceListener(metadata(ImmutableMap.of("key0", "val0",
                                                                "key1", "val1", "key2", "val2")));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080, metadata(ImmutableMap.of("key0", "val0",
                                                                                      "key1", "val1"))),
                                 endpoint("127.0.0.1", 8081, metadata(ImmutableMap.of("key0", "val0",
                                                                                      "key2", "val2"))));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key0", "key1", "key2"))
                                       .toBuilder()
                                       .setFallbackPolicy(LbSubsetSelectorFallbackPolicy.DEFAULT_SUBSET)
                                       .build());
        if (defaultSubset != null) {
            lbSubsetConfig = lbSubsetConfig.toBuilder().setDefaultSubset(defaultSubset).build();
        }
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint1);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint2);
        }
    }

    @Test
    void fallbackAny() {
        final Listener listener =
                staticResourceListener(metadata(ImmutableMap.of("key1", "val1")));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080, metadata(ImmutableMap.of("key2", "val2"))),
                                 endpoint("127.0.0.1", 8081, metadata(ImmutableMap.of("key3", "val3"))));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key1"))
                                       .toBuilder()
                                       .setFallbackPolicy(LbSubsetSelectorFallbackPolicy.ANY_ENDPOINT)
                                       .build());
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
        }
    }

    private static Stream<Arguments> metadataFallbackParams() {
        return Stream.of(
                Arguments.of(fallbackListValue(ImmutableMap.of("key2", "val2")),
                             Endpoint.of("127.0.0.1", 8081),
                             Endpoint.of("127.0.0.1", 8081)),
                Arguments.of(fallbackListValue(ImmutableMap.of("key3", "val3"),
                                               ImmutableMap.of("key2", "val2")),
                             Endpoint.of("127.0.0.1", 8081),
                             Endpoint.of("127.0.0.1", 8081)),
                Arguments.of(Value.getDefaultInstance(),
                             Endpoint.of("127.0.0.1", 8080),
                             Endpoint.of("127.0.0.1", 8080)),
                Arguments.of(fallbackListValue(ImmutableMap.of("key4", "val4")), null, null)
        );
    }

    @ParameterizedTest
    @MethodSource("metadataFallbackParams")
    void metadataFallback(Value metadataFallback, Endpoint endpoint1, Endpoint endpoint2) {
        final Listener listener =
                staticResourceListener(metadata(Struct.newBuilder()
                                                      .putFields("key1", stringValue("val1"))
                                                      .putFields(ENVOY_LB_FALLBACK_LIST, metadataFallback)
                                                      .build()));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080, metadata(ImmutableMap.of("key1", "val1"))),
                                 endpoint("127.0.0.1", 8081, metadata(ImmutableMap.of("key2", "val2"))));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key1")),
                               lbSubsetSelector(ImmutableList.of("key2")))
                        .toBuilder()
                        .setMetadataFallbackPolicy(LbSubsetMetadataFallbackPolicy.FALLBACK_LIST)
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint1);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint2);
        }
    }

    @Test
    void subsetAny() {
        final Listener listener =
                staticResourceListener(metadata(ImmutableMap.of("key1", "val1")));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080),
                                 endpoint("127.0.0.1", 8081));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key1")))
                        .toBuilder()
                        .setFallbackPolicy(LbSubsetFallbackPolicy.ANY_ENDPOINT)
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
        }
    }

    @ParameterizedTest
    @MethodSource("fallbackKeysDefaultArgs")
    void fallbackSubsetTest(@Nullable Struct defaultSubset, @Nullable Endpoint endpoint1,
                            @Nullable Endpoint endpoint2) {
        final Listener listener =
                staticResourceListener(metadata(ImmutableMap.of("key0", "val0",
                                                                "key1", "val1", "key2", "val2")));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080, metadata(ImmutableMap.of("key0", "val0",
                                                                                      "key1", "val1"))),
                                 endpoint("127.0.0.1", 8081, metadata(ImmutableMap.of("key0", "val0",
                                                                                      "key2", "val2"))));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key0", "key1", "key2")))
                        .toBuilder()
                        .setFallbackPolicy(LbSubsetFallbackPolicy.DEFAULT_SUBSET)
                        .build();
        if (defaultSubset != null) {
            lbSubsetConfig = lbSubsetConfig.toBuilder().setDefaultSubset(defaultSubset).build();
        }
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint1);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint2);
        }
    }

    @Test
    void panic() {
        final Listener listener =
                staticResourceListener(metadata(ImmutableMap.of("key1", "val1")));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080),
                                 endpoint("127.0.0.1", 8081));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key1")))
                        .toBuilder()
                        .setPanicModeAny(true)
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
        }
    }

    private static Stream<Arguments> listAsAnySubsetParams() {
        return Stream.of(
                Arguments.of(true, struct(ImmutableMap.of("key1", "val1",
                                                          "key4", "val4")),
                             Endpoint.of("127.0.0.1", 8080),
                             Endpoint.of("127.0.0.1", 8080)),
                Arguments.of(false, struct(ImmutableMap.of("key1", "val1",
                                                           "key4", "val4")),
                             null, null),
                Arguments.of(true, Struct.newBuilder()
                                         .putFields("key1", LIST_VALUE)
                                         .putFields("key4", stringValue("val4"))
                                         .build(),
                             null, null),
                Arguments.of(false, Struct.newBuilder()
                                          .putFields("key1", LIST_VALUE)
                                          .putFields("key4", stringValue("val4"))
                                          .build(),
                             Endpoint.of("127.0.0.1", 8080),
                             Endpoint.of("127.0.0.1", 8080))
        );
    }

    @ParameterizedTest
    @MethodSource("listAsAnySubsetParams")
    void listAsAnySubset(boolean listAsAny, Struct struct, @Nullable Endpoint endpoint1,
                         @Nullable Endpoint endpoint2) {

        final Listener listener = staticResourceListener(metadata(struct));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080,
                                          metadata(Struct.newBuilder()
                                                         .putFields("key1", LIST_VALUE)
                                                         .putFields("key4", stringValue("val4"))
                                                         .build())),
                                 endpoint("127.0.0.1", 8081,
                                          metadata(Struct.newBuilder()
                                                         .putFields("key1", LIST_VALUE)
                                                         .putFields("key4", stringValue("val3"))
                                                         .build())));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key1", "key4")))
                        .toBuilder()
                        .setListAsAny(listAsAny)
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint1);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint2);
        }
    }

    @ParameterizedTest
    @MethodSource("listAsAnySubsetParams")
    void defaultSubsetListAsAny(boolean listAsAny, Struct struct, @Nullable Endpoint endpoint1,
                                @Nullable Endpoint endpoint2) {

        final Listener listener =
                staticResourceListener(metadata(struct(ImmutableMap.of("key1", "val1",
                                                                       "key4", "val4"))));

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080,
                                          metadata(Struct.newBuilder()
                                                         .putFields("key1", LIST_VALUE)
                                                         .putFields("key4", stringValue("val4"))
                                                         .build())),
                                 endpoint("127.0.0.1", 8081,
                                          metadata(Struct.newBuilder()
                                                         .putFields("key1", LIST_VALUE)
                                                         .putFields("key4", stringValue("val3"))
                                                         .build())));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final LbSubsetConfig lbSubsetConfig =
                lbSubsetConfig(lbSubsetSelector(ImmutableList.of("key1", "key5")))
                        .toBuilder()
                        .setListAsAny(listAsAny)
                        .setDefaultSubset(struct)
                        .setFallbackPolicy(LbSubsetFallbackPolicy.DEFAULT_SUBSET)
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setLbSubsetConfig(lbSubsetConfig).build();

        final Bootstrap bootstrap = XdsTestResources.staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint1);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint2);
        }
    }
}
