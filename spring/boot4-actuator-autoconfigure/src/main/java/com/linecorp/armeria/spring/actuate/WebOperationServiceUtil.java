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
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;

/**
 * A utility class to support {@link WebServerNamespace} which was introduced from Spring Boot 2.6+.
 * The methods in this class are called only if {@link WebOperationService#HAS_WEB_SERVER_NAMESPACE} is
 * {@code true}.
 */
final class WebOperationServiceUtil {

    static OperationArgumentResolver namespaceResolver(boolean server) {
        return OperationArgumentResolver.of(WebServerNamespace.class,
                                            () -> server ? WebServerNamespace.SERVER
                                                         : WebServerNamespace.MANAGEMENT);
    }

    private WebOperationServiceUtil() {}
}
