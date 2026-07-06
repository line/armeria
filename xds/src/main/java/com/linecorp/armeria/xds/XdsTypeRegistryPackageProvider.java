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
 * SPI for providing additional Java packages to scan when building the
 * {@link com.google.protobuf.util.JsonFormat.TypeRegistry TypeRegistry} used by {@link XdsResourceReader}.
 *
 * <p>By default, {@link XdsResourceReader} scans the {@code io.envoyproxy}, {@code com.github.udpa}
 * and {@code com.github.xds} packages. Implement this interface and register it via
 * {@link java.util.ServiceLoader} to add custom packages containing protobuf types that should
 * be resolvable via {@code @type} annotations in YAML/JSON.
 *
 * <p>Packages are matched by prefix — specifying {@code "com.mycompany.xds"} will also cover
 * {@code "com.mycompany.xds.v1"}, {@code "com.mycompany.xds.config"}, etc.
 *
 * <p>Example usage:
 * <pre>{@code
 * public class MyPackageProvider implements XdsTypeRegistryPackageProvider {
 *     @Override
 *     public Iterable<String> packages() {
 *         return List.of("com.mycompany.xds");
 *     }
 * }
 * }</pre>
 *
 * <p>Then register in {@code META-INF/services/com.linecorp.armeria.xds.XdsTypeRegistryPackageProvider}:
 * <pre>{@code
 * com.mycompany.xds.MyPackageProvider
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface XdsTypeRegistryPackageProvider {

    /**
     * Returns the Java packages to scan for protobuf types.
     * Each package is matched by prefix — sub-packages are included automatically.
     */
    Iterable<String> packages();
}
