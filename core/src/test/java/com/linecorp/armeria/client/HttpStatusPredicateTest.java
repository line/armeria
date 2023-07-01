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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpStatus;

class HttpStatusPredicateTest {

    @Test
    void httpStatusIsEqualToTestArgument() {
        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(200))
                                      .test(HttpStatus.valueOf(200))).isTrue();

        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(400))
                                      .test(HttpStatus.valueOf(400))).isTrue();

        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(0))
                                      .test(HttpStatus.valueOf(0))).isTrue();

        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(1000))
                                      .test(HttpStatus.valueOf(1000))).isTrue();
    }

    @Test
    void httpStatusIsNotEqualToTestArgument() {
        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(200))
                                      .test(HttpStatus.valueOf(400))).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(400))
                                      .test(HttpStatus.valueOf(200))).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(0))
                                      .test(HttpStatus.valueOf(200))).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.valueOf(1000))
                                      .test(HttpStatus.valueOf(200))).isFalse();
    }

    @Test
    void statusMethodReturnHttpStatus() {
        assertThat(HttpStatusPredicate.of(HttpStatus.OK).status())
                .isEqualTo(HttpStatus.OK);
    }
}
