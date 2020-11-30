/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.consul;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.consul.ConsulClientBuilder;

/**
 * Sets properties for building a Consul client.
 */
@UnstableApi
public interface ConsulConfigSetters {
    /**
     * Sets the specified Consul's API version.
     * @param consulApiVersion the version of Consul API service, default: {@value
     *                         ConsulClientBuilder#DEFAULT_CONSUL_API_VERSION}
     */
    ConsulConfigSetters consulApiVersion(String consulApiVersion);

    /**
     * Sets the specified token for Consul's API.
     * @param consulToken the token for accessing Consul API, default: {@code null}
     */
    ConsulConfigSetters consulToken(String consulToken);
}
