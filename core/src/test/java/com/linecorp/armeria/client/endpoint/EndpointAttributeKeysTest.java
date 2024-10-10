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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys;

class EndpointAttributeKeysTest {

    @ParameterizedTest
    @CsvSource(value =  {"true,true", "false,true", "true,false", "false,false"}, delimiterString = ",")
    void testHealthCheckAttributes(boolean healthy, boolean degraded) {
        final Attributes attributes = EndpointAttributeKeys.healthCheckAttributes(healthy, degraded);
        assertThat(attributes.attr(EndpointAttributeKeys.HEALTHY_ATTR)).isEqualTo(healthy);
        assertThat(attributes.attr(EndpointAttributeKeys.DEGRADED_ATTR)).isEqualTo(degraded);
    }
}
