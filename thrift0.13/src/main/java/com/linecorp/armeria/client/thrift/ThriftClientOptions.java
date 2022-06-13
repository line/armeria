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

package com.linecorp.armeria.client.thrift;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * {@link ClientOption}s to control Thrift-specific behavior.
 */
@UnstableApi
public final class ThriftClientOptions {

    /**
     * The maximum allowed number of bytes to read from the transport for
     * variable-length fields (such as strings or binary).
     * If unspecified, the value of {@link ClientOptions#maxResponseLength()} will be used instead.
     */
    public static final ClientOption<Integer> MAX_RESPONSE_STRING_LENGTH =
            ClientOption.define("THRIFT_MAX_STRING_LENGTH", -1);

    /**
     * The maximum allowed number of containers to read from the transport for maps, sets and lists.
     * If unspecified, the value of {@link ClientOptions#maxResponseLength()} will be used instead.
     */
    public static final ClientOption<Integer> MAX_RESPONSE_CONTAINER_LENGTH =
            ClientOption.define("THRIFT_MAX_CONTAINER_LENGTH", -1);

    private ThriftClientOptions() {}
}
