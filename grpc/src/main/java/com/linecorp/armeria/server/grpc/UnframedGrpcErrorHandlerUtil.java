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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServiceRequestContext;

final class UnframedGrpcErrorHandlerUtil {
    /**
     * Ensure that unframedGrpcStatusMappingFunction never returns null by falling back to the default.
     */
    static UnframedGrpcStatusMappingFunction withDefault(
            UnframedGrpcStatusMappingFunction statusMappingFunction) {

        requireNonNull(statusMappingFunction, "statusMappingFunction");
        if (statusMappingFunction == UnframedGrpcStatusMappingFunction.of()) {
            return statusMappingFunction;
        }
        return statusMappingFunction.orElse(UnframedGrpcStatusMappingFunction.of());
    }

    @Nullable
    static Throwable responseCause(ServiceRequestContext ctx) {
        final RequestLogAccess log = ctx.log();
        if (log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
            return log.partial().responseCause();
        } else {
            return null;
        }
    }

    private UnframedGrpcErrorHandlerUtil() {}
}
