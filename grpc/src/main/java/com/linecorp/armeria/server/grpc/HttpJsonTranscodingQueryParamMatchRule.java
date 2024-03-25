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

package com.linecorp.armeria.server.grpc;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A naming rule to map {@link QueryParams} of an {@link HttpRequest} to fields in a {@link Message} for
 * HTTP-JSON transcoding endpoint.
 */
@UnstableApi
public enum HttpJsonTranscodingQueryParamMatchRule {
    /**
     * Converts field names that are
     * <a href="https://developers.google.com/protocol-buffers/docs/style#message_and_field_names">underscore_separated</a>
     * into lowerCamelCase before matching with {@link QueryParams} of an {@link HttpRequest}.
     *
     * <p>Note that field names which aren't {@code underscore_separated} may fail to
     * convert correctly to lowerCamelCase. Therefore, don't use this option if you aren't following
     * Protocol Buffer's
     * <a href="https://developers.google.com/protocol-buffers/docs/style">naming conventions</a>.
     */
    LOWER_CAMEL_CASE,
    /**
     * Uses the original fields in .proto files to match {@link QueryParams} of an {@link HttpRequest}.
     */
    ORIGINAL_FIELD
}
