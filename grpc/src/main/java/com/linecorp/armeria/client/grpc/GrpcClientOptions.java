/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import com.linecorp.armeria.client.ClientOption;

/**
 * {@link ClientOption}s to control gRPC-specific behavior.
 */
public final class GrpcClientOptions {

    /**
     * The maximum size, in bytes, of messages coming in a response.
     */
    public static final ClientOption<Integer> MAX_INBOUND_MESSAGE_SIZE_BYTES = ClientOption.valueOf(
            "MAX_INBOUND_MESSAGE_SIZE_BYTES");

    /**
     * The maximum size, in bytes, of messages sent in a request.
     */
    public static final ClientOption<Integer> MAX_OUTBOUND_MESSAGE_SIZE_BYTES = ClientOption.valueOf(
            "MAX_OUTBOUND_MESSAGE_SIZE_BYTES");

    private GrpcClientOptions() {}
}
