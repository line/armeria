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

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A JSON-RPC method invokable message.
 */
@UnstableApi
public interface JsonRpcMethodInvokable {

    /**
     * Returns the JSON-RPC method name.
     */
    @JsonProperty
    String method();

    /**
     * Returns the parameters for the JSON-RPC method.
     */
    @JsonProperty
    JsonRpcParameters params();
}
