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

/**
 * SPI for providing an {@link XdsExtensionFactory} to the {@link XdsExtensionRegistry}.
 *
 * <p>Implement this interface and register it via {@link java.util.ServiceLoader} to supply
 * a custom extension factory (HTTP filter, config source, cluster type, etc.) without
 * passing it through {@link XdsBootstrapBuilder#extensionFactories}.
 *
 * <p>Example usage:
 * <pre>{@code
 * public class MyConfigSourceProvider implements XdsExtensionFactoryProvider {
 *     @Override
 *     public XdsExtensionFactory newFactory() {
 *         return new MyConfigSourceFactory();
 *     }
 * }
 * }</pre>
 *
 * <p>Then register in
 * {@code META-INF/services/com.linecorp.armeria.xds.XdsExtensionFactoryProvider}:
 * <pre>{@code
 * com.mycompany.MyConfigSourceProvider
 * }</pre>
 */
@UnstableApi
public interface XdsExtensionFactoryProvider {

    /**
     * Creates a new {@link XdsExtensionFactory} instance.
     * The returned factory may be any subtype of {@link XdsExtensionFactory}, such as
     * {@link com.linecorp.armeria.xds.filter.HttpFilterFactory},
     * {@link com.linecorp.armeria.xds.configsource.SotwConfigSourceSubscriptionFactory}, or
     * {@link com.linecorp.armeria.xds.client.endpoint.ClusterTypeFactory}.
     */
    XdsExtensionFactory newFactory();
}
