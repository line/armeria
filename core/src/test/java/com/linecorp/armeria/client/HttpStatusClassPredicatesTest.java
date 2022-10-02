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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

class HttpStatusClassPredicatesTest {

    @Test
    void httpStatusClassIsEqualToTestArgument() {
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(100))
                           .test(HttpStatus.valueOf(100))).isTrue();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(200))
                           .test(HttpStatus.valueOf(200))).isTrue();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(300))
                           .test(HttpStatus.valueOf(300))).isTrue();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(400))
                           .test(HttpStatus.valueOf(400))).isTrue();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(500))
                           .test(HttpStatus.valueOf(500))).isTrue();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(600))
                           .test(HttpStatus.valueOf(600))).isTrue();
    }

    @Test
    void httpStatusClassIsNotEqualToTestArgument() {
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(200))
                           .test(HttpStatus.valueOf(300))).isFalse();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(300))
                           .test(HttpStatus.valueOf(200))).isFalse();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(100))
                           .test(HttpStatus.valueOf(600))).isFalse();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(600))
                           .test(HttpStatus.valueOf(100))).isFalse();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(600))
                           .test(HttpStatus.valueOf(200))).isFalse();
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(100))
                           .test(HttpStatus.valueOf(300))).isFalse();
    }

    @Test
    void statusClassMethodReturnHttpStatusClass() {
        assertThat(HttpStatusClassPredicates.of(HttpStatusClass.valueOf(200)).statusClass())
                .isEqualTo(HttpStatusClass.valueOf(200));
    }
}
