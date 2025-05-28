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
 * Provides the core components for building and running JSON-RPC 2.0 services within the Armeria framework.
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li><b>Service Definition:</b>
 *     Services can be implemented as standard Armeria {@link com.linecorp.armeria.server.HttpService}s
 *     or using annotated service objects. The {@link com.linecorp.armeria.server.jsonrpc.JsonRpcServiceBuilder}
 *     is the primary entry point for configuring and registering these services.</li>
 *   <li><b>Request Handling:</b>
 *     Includes robust parsing of JSON-RPC requests
 *     ({@link com.linecorp.armeria.server.jsonrpc.JsonRpcRequestParser}),
 *     supporting single requests, batch requests, and notifications.</li>
 *   <li><b>Routing and Dispatch:</b>
 *     The {@link com.linecorp.armeria.server.jsonrpc.JsonRpcService}
 *     (typically {@link com.linecorp.armeria.server.jsonrpc.SimpleJsonRpcService})
 *     dispatches requests to the appropriate backend methods
 *     based on the "method" field in the JSON-RPC request
 *     and the configured path prefixes.</li>
 *   <li><b>Response Formatting:</b>
 *     The {@link com.linecorp.armeria.server.jsonrpc.JsonRpcServiceDecorator}
 *     ensures that responses from delegate
 *     services are correctly formatted as JSON-RPC responses, including handling of IDs and errors.</li>
 *   <li><b>Error Handling:</b>
 *     Defines specific exceptions
 *     like {@link com.linecorp.armeria.server.jsonrpc.JsonRpcRequestParseException} and
 *     {@link com.linecorp.armeria.server.jsonrpc.JsonRpcServiceNotFoundException}
 *     for structured error reporting.</li>
 * </ul>
 *
 * <h2>Getting Started:</h2>
 * <p>
 *   To create a JSON-RPC service, typically you would start
 *   with {@link com.linecorp.armeria.server.jsonrpc.JsonRpcService
 *   #builder(com.linecorp.armeria.server.ServerBuilder)}
 *   and add your service implementations:
 * </p>
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * // ... other server configurations ...
 *
 * MyJsonRpcMethods myMethods = new MyJsonRpcMethods(); // Your annotated service object
 *
 * sb.service(JsonRpcService.builder(sb)
 *                        .addAnnotatedService("/myRpc", myMethods)
 *                        .build());
 * // ...
 * Server server = sb.build();
 * }</pre>
 *
 * @see com.linecorp.armeria.server.jsonrpc.JsonRpcService
 * @see com.linecorp.armeria.server.jsonrpc.JsonRpcServiceBuilder
 * @see com.linecorp.armeria.common.jsonrpc.JsonRpcRequest
 * @see com.linecorp.armeria.common.jsonrpc.JsonRpcResponse
 */
@NonNullByDefault
package com.linecorp.armeria.server.jsonrpc;

import com.linecorp.armeria.common.annotation.NonNullByDefault;
