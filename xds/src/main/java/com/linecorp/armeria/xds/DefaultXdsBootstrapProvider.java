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
 * A default {@link XdsBootstrapProvider} that is registered via SPI and loads bootstrap
 * configuration from {@value #DEFAULT_RESOURCE} on the classpath. The bootstrap is registered
 * under the name {@value #DEFAULT_NAME}.
 *
 * <p>To use, place your bootstrap YAML at the root of the classpath as {@value #DEFAULT_RESOURCE}.
 */
@UnstableApi
public final class DefaultXdsBootstrapProvider implements XdsBootstrapProvider {

    /**
     * The default bootstrap name ({@value}).
     */
    public static final String DEFAULT_NAME = "default";

    /**
     * The default classpath resource path for bootstrap configuration ({@value}).
     */
    public static final String DEFAULT_RESOURCE = "xds-bootstrap.yaml";

    @Override
    public String name() {
        return DEFAULT_NAME;
    }

    @Override
    public String resource() {
        return DEFAULT_RESOURCE;
    }
}
