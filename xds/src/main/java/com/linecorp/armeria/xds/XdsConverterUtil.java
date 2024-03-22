/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class XdsConverterUtil {

    private XdsConverterUtil() {}

    static void validateConfigSource(@Nullable ConfigSource configSource) {
        if (configSource == null || configSource.equals(ConfigSource.getDefaultInstance())) {
            return;
        }
        checkArgument(configSource.hasAds() || configSource.hasApiConfigSource(),
                      "Only configSource with Ads or ApiConfigSource is supported for %s", configSource);
        if (configSource.hasApiConfigSource()) {
            final ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
            final ApiType apiType = apiConfigSource.getApiType();
            checkArgument(apiType == ApiType.GRPC || apiType == ApiType.AGGREGATED_GRPC,
                          "Unsupported apiType %s. Only GRPC and AGGREGATED_GRPC are supported.", configSource);
            checkArgument(apiConfigSource.getGrpcServicesCount() > 0,
                          "At least once GrpcService is required for ApiConfigSource for %s", configSource);
            apiConfigSource.getGrpcServicesList().forEach(
                    grpcService -> checkArgument(grpcService.hasEnvoyGrpc(),
                                                 "Only envoyGrpc is supported for %s", grpcService));
        }
    }
}
