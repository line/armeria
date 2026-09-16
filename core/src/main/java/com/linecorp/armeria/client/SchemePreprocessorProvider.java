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

package com.linecorp.armeria.client;

import java.net.URI;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * SPI for handling custom URI schemes (e.g., {@code "xds"}) that are not standard
 * {@link SessionProtocol}s. Implementations are discovered via
 * {@link java.util.ServiceLoader} and produce preprocessors from a URI.
 *
 * <p>For example, an xDS module would implement this to handle URIs like
 * {@code "gproto+xds:///my-service"} by creating appropriate preprocessors.
 */
@UnstableApi
public interface SchemePreprocessorProvider {

    /**
     * Returns the scheme text handled by this provider (e.g., {@code "xds"}).
     * Must be lowercase.
     */
    String scheme();

    /**
     * Returns an {@link HttpPreprocessor} for the specified URI.
     */
    HttpPreprocessor preprocessor(URI uri);

    /**
     * Returns an {@link RpcPreprocessor} for the specified URI.
     */
    RpcPreprocessor rpcPreprocessor(URI uri);
}
