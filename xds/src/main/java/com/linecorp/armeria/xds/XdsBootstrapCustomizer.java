/*
 * Copyright 2026 LY Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * SPI for customizing the {@link Bootstrap} used by {@link XdsBootstrap} instances.
 * Implementations are loaded via {@link java.util.ServiceLoader} and should be listed in
 * {@code META-INF/services/com.linecorp.armeria.xds.XdsBootstrapCustomizer}.
 *
 * <p>Each customizer's {@link #customize(Bootstrap)} method is called in sequence during
 * {@link XdsBootstrapBuilder#build()}, receiving the bootstrap produced by the previous
 * customizer (or the original bootstrap for the first customizer). This can be useful
 * if modules want to add their own static resources.
 *
 * <p>Example:
 * <pre>{@code
 * public class InternalCpCustomizer implements XdsBootstrapCustomizer {
 *     @Override
 *     public Bootstrap customize(Bootstrap bootstrap) {
 *         Cluster cluster = Cluster.newBuilder()
 *                 .setName("internal-control-plane")
 *                 ...
 *                 .build();
 *         return bootstrap.toBuilder()
 *                 .setStaticResources(
 *                         bootstrap.getStaticResources().toBuilder()
 *                                  .addClusters(cluster))
 *                 .build();
 *     }
 * }
 * }</pre>
 *
 * @see XdsBootstrapBuilder
 */
@UnstableApi
public interface XdsBootstrapCustomizer {

    /**
     * Customizes the given {@link Bootstrap} and returns the modified version.
     *
     * @param bootstrap the current bootstrap, possibly already modified by a previous customizer
     * @return the customized bootstrap
     */
    Bootstrap customize(Bootstrap bootstrap);
}
