/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.spring.actuate;

import org.springframework.boot.actuate.endpoint.OperationArgumentResolver;
import org.springframework.boot.actuate.endpoint.ProducibleOperationArgumentResolver;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

/**
 * A utility class to support {@link OperationArgumentResolver} which was introduced from Spring Boot 2.5+.
 * The method in this class is called only if
 * {@code WebOperationService#HAS_PRODUCIBLE_OPERATION_ARGUMENT_RESOLVER} is {@code true}.
 */
final class OperationArgumentResolverUtil {

    static OperationArgumentResolver acceptHeadersResolver(HttpHeaders headers) {
        return new ProducibleOperationArgumentResolver(() -> headers.getAll(HttpHeaderNames.ACCEPT));
    }

    private OperationArgumentResolverUtil() {}
}
