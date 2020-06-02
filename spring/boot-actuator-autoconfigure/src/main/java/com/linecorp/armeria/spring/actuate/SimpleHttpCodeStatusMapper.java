/*
 * Copyright 2020 LINE Corporation
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

/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.spring.actuate;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.health.Status;

import com.google.common.collect.ImmutableMap;

/**
 * Copied from spring-boot-actuator 2.3.0 to avoid breaking compatibility.
 * We used spring-boot-actuator's HealthStatusHttpMapper previously
 * but it has been deprecated since 2.2.0 and deleted since 2.3.0.
 */
class SimpleHttpCodeStatusMapper {

    private final Map<String, Integer> mappings;

    SimpleHttpCodeStatusMapper() {
        mappings = ImmutableMap.of(
                Status.DOWN.getCode(), WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE,
                Status.OUT_OF_SERVICE.getCode(), WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE
        );
    }

    int getStatusCode(Status status) {
        return mappings.getOrDefault(status.getCode(), WebEndpointResponse.STATUS_OK);
    }
}
