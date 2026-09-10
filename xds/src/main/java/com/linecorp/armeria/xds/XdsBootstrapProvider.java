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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * SPI provider for registering named {@link XdsBootstrap} instances.
 * Implementations are loaded via {@link java.util.ServiceLoader} and should be listed in
 * {@code META-INF/services/com.linecorp.armeria.xds.XdsBootstrapProvider}.
 *
 * <p>The simplest way to implement a provider is to override {@link #name()} and
 * {@link #resource()} to point to a classpath YAML/JSON resource:
 * <pre>{@code
 * public class MyProvider implements XdsBootstrapProvider {
 *     @Override
 *     public String name() { return "default"; }
 *
 *     @Override
 *     public String resource() { return "xds-bootstrap.yaml"; }
 * }
 * }</pre>
 *
 * <p>For full control, override {@link #newBootstrap()} directly instead.
 */
@UnstableApi
public interface XdsBootstrapProvider {

    /**
     * Returns the name used to look up this bootstrap from xDS URIs.
     * For example, returning {@code "my-bootstrap"} means the URI
     * {@code xds://my-bootstrap/listener1} will use this bootstrap.
     */
    String name();

    /**
     * Returns the classpath resource path of a YAML/JSON bootstrap configuration,
     * or {@code null} if {@link #newBootstrap()} is overridden directly.
     * The resource is loaded via the provider's class loader.
     *
     * @return the classpath resource path, e.g. {@code "xds-bootstrap.yaml"}
     */
    @Nullable
    default String resource() {
        return null;
    }

    /**
     * Creates a new {@link XdsBootstrap} instance.
     *
     * <p>The default implementation loads the classpath resource specified by
     * {@link #resource()}, parses it as a {@link Bootstrap}, and creates
     * an {@link XdsBootstrap} from it.
     *
     * @throws IllegalStateException if {@link #resource()} returns {@code null}
     */
    default XdsBootstrap newBootstrap() {
        final String resource = resource();
        if (resource == null) {
            throw new IllegalStateException(
                    "Either resource() or newBootstrap() must be overridden in " + getClass().getName());
        }
        final Bootstrap bootstrap = XdsResourceReader.fromResource(
                resource, getClass().getClassLoader(), Bootstrap.class);
        return XdsBootstrap.of(bootstrap);
    }
}
