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
package com.linecorp.armeria.server.jsonrpc;

/**
 * Exception thrown when a specific JSON-RPC method cannot be mapped to a registered service.
 */
public class JsonRpcServiceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 5996593317006754659L;
    private final String lookupPath;

    /**
     * Creates a new instance.
     *
     * @param message the detail message explaining why the service was not found.
     * @param lookupPath the full path (e.g., "/servicePrefix/methodName") that was used in the attempt
     *                   to find a registered service, and for which no mapping was found.
     */
    public JsonRpcServiceNotFoundException(String message,
                                       String lookupPath) {
        super(message);
        this.lookupPath = lookupPath;
    }

    /**
     * Returns the path that was used when attempting to find the service, but for which no mapping was found.
     *
     * @return the lookup path for which no service was found.
     */
    public String getLookupPath() {
        return lookupPath;
    }
}
