/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.spring.actuate;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Settings for actuator.
 */
@ConfigurationProperties(prefix = "armeria.management.server")
public class ArmeriaManagementServerProperties {

    /**
     * Management endpoint HTTP port.
     */
    @Nullable
    private Integer port;

    /**
     * Returns the management port.
     */
    @Nullable
    public Integer getPort() {
        return this.port;
    }

    /**
     * Sets the port of the management server.
     */
    public void setPort(Integer port) {
        this.port = port;
    }
}
