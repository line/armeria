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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HttpEntityTest {

    @Test
    void requestEntity() {
        final RequestEntity<String> request1 =
                RequestEntity.of(RequestHeaders.of(HttpMethod.PUT, "/"), "hello", HttpHeaders.of("foo", "bar"));

        assertThat(request1.headers().method()).isEqualTo(HttpMethod.PUT);
        assertThat(request1.content()).isEqualTo("hello");
        assertThat(request1.hasContent()).isTrue();
        assertThat(request1.trailers().get("foo")).isEqualTo("bar");

        final RequestEntity<String> request2 =
                RequestEntity.of(RequestHeaders.of(HttpMethod.PUT, "/"), "hello", HttpHeaders.of("foo", "bar"));
        assertThat(request1).isEqualTo(request2);

        final RequestEntity<Void> request3 =
                RequestEntity.of(RequestHeaders.of(HttpMethod.PUT, "/"));
        assertThat(request3.hasContent()).isFalse();
        assertThatThrownBy(request3::content)
                .isInstanceOf(NoHttpContentException.class)
                .hasMessageContaining("No content present");
    }
    
    @Test
    void responseEntity() {
        final ResponseEntity<String> request1 =
                ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK), "hello", HttpHeaders.of("foo", "bar"));

        assertThat(request1.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(request1.content()).isEqualTo("hello");
        assertThat(request1.hasContent()).isTrue();
        assertThat(request1.trailers().get("foo")).isEqualTo("bar");

        final ResponseEntity<String> request2 =
                ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK), "hello", HttpHeaders.of("foo", "bar"));
        assertThat(request1).isEqualTo(request2);

        final ResponseEntity<Void> request3 = ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK));
        assertThat(request3.hasContent()).isFalse();
        assertThatThrownBy(request3::content)
                .isInstanceOf(NoHttpContentException.class)
                .hasMessageContaining("No content present");
    }
}
