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

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Utilities for introspecting a gRPC request.
 */
final class GrpcRequestUtil {

    @Nullable
    static String determineMethod(ServiceRequestContext ctx) {
        // Remove the leading slash of the path and get the fully qualified method name
        final String path = ctx.mappedPath();
        if (path.charAt(0) != '/') {
            return null;
        }

        return path.substring(1);
    }

    private GrpcRequestUtil() {}
}
