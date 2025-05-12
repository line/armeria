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
/**
 * Provides common data model classes for Armeria's JSON-RPC 2.0 implementation,
 * designed to be shared between client-side and server-side components.
 *
 * <p>This package contains Plain Old Java Objects (POJOs) that represent the fundamental
 * structures of JSON-RPC 2.0 messages:
 * <ul>
 *   <li>{@link com.linecorp.armeria.common.jsonrpc.JsonRpcRequest}: Represents a JSON-RPC request object.</li>
 *   <li>{@link com.linecorp.armeria.common.jsonrpc.JsonRpcResponse}: Represents a JSON-RPC response object,
 *       which can indicate either success (with a result) or failure (with an error).</li>
 *   <li>{@link com.linecorp.armeria.common.jsonrpc.JsonRpcError}: Represents the error object included in
 *       a response when a request fails.</li>
 *   <li>{@link com.linecorp.armeria.common.jsonrpc.JsonRpcErrorCode}: An enum defining standard
 *       JSON-RPC 2.0 error codes.</li>
 * </ul>
 * These classes are annotated for straightforward serialization to and deserialization from JSON
 * using the Jackson library.
 *
 * <p>Utility classes like {@link com.linecorp.armeria.common.jsonrpc.JsonRpcUtil} and
 * {@link com.linecorp.armeria.common.jsonrpc.JsonRpcResponseFactory} offer helper methods
 * for creating, parsing, and transforming these JSON-RPC messages.
 * </p>
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 */
@NonNullByDefault
package com.linecorp.armeria.common.jsonrpc;

import com.linecorp.armeria.common.annotation.NonNullByDefault;
