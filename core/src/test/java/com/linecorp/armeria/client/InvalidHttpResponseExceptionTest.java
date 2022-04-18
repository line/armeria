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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.ResponseHeaders;

import io.netty.buffer.Unpooled;

class InvalidHttpResponseExceptionTest {

    @Test
    void shouldNotAllowPooledObjects() {
        try (HttpData content = HttpData.wrap(Unpooled.wrappedBuffer("foo".getBytes()))) {
            final AggregatedHttpResponse response = AggregatedHttpResponse.of(ResponseHeaders.of(200), content);
            assertThatThrownBy(() -> new InvalidHttpResponseException(response))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            "Cannot create an InvalidHttpResponseException with the pooled content");

            assertThatThrownBy(() -> new InvalidHttpResponseException(response, "foo", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            "Cannot create an InvalidHttpResponseException with the pooled content");
        }
    }

    @Test
    void createWithNonPooledObject() {
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(ResponseHeaders.of(200));
        assertThat(new InvalidHttpResponseException(response).response()).isEqualTo(response);
    }
}
