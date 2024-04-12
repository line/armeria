/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The types of responses that can be sent when handling errors in an unframed gRPC service.
 *
 * <p>When multiple {@code UnframedGrpcErrorResponseType} values are selected, the actual response type
 * is determined by the response's {@code contentType}.
 */
@UnstableApi
public enum UnframedGrpcErrorResponseType {
    /**
     * The error response will be formatted as a JSON object.
     */
    JSON,

    /**
     * The error response will be sent as plain text.
     */
    PLAINTEXT,
}
