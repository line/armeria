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

package com.linecorp.armeria.spring.actuate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.health.Status;

/**
 * Tests for {@link SimpleHttpCodeStatusMapper}.
 */
public class SimpleHttpCodeStatusMapperTest {

    @Test
    public void testGetStatusCode() {
        final SimpleHttpCodeStatusMapper mapper = new SimpleHttpCodeStatusMapper();
        assertThat(mapper.getStatusCode(Status.UNKNOWN)).isEqualTo(WebEndpointResponse.STATUS_OK);
        assertThat(mapper.getStatusCode(Status.UP)).isEqualTo(WebEndpointResponse.STATUS_OK);
        assertThat(mapper.getStatusCode(Status.DOWN)).isEqualTo(WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE);
        assertThat(mapper.getStatusCode(Status.OUT_OF_SERVICE))
                .isEqualTo(WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE);
    }
}
