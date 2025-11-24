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
package com.linecorp.armeria.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonValue;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The version of the JSON-RPC specification.
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 */
@UnstableApi
public enum JsonRpcVersion {
    JSON_RPC_2_0("2.0");

    private final String version;

    JsonRpcVersion(String version) {
        this.version = version;
    }

    /**
     * Returns the string representation of the JSON-RPC version.
     */
    @JsonValue
    public String getVersion() {
        return version;
    }
}
