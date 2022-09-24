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

class HttpStatusPredicateTest {

    @Test
    public void httpStatusIsEqualToTestArgument() {
        assertThat(HttpStatusPredicate.of(HttpStatus.OK)
                           .test(HttpStatus.OK)).isTrue();

        assertThat(HttpStatusPredicate.of(HttpStatus.MULTIPLE_CHOICES)
                           .test(HttpStatus.MULTIPLE_CHOICES)).isTrue();

        assertThat(HttpStatusPredicate.of(HttpStatus.BAD_REQUEST)
                           .test(HttpStatus.BAD_REQUEST)).isTrue();

        assertThat(HttpStatusPredicate.of(HttpStatus.INTERNAL_SERVER_ERROR)
                           .test(HttpStatus.INTERNAL_SERVER_ERROR)).isTrue();

        assertThat(HttpStatusPredicate.of(HttpStatus.UNKNOWN)
                           .test(HttpStatus.UNKNOWN)).isTrue();
    }

    @Test
    public void httpStatusIsNotEqualToTestArgument() {
        assertThat(HttpStatusPredicate.of(HttpStatus.OK)
                           .test(HttpStatus.MULTIPLE_CHOICES)).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.MULTIPLE_CHOICES)
                           .test(HttpStatus.OK)).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.CONTINUE)
                           .test(HttpStatus.UNKNOWN)).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.UNKNOWN)
                           .test(HttpStatus.CONTINUE)).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.UNKNOWN)
                           .test(HttpStatus.OK)).isFalse();

        assertThat(HttpStatusPredicate.of(HttpStatus.CONTINUE)
                           .test(HttpStatus.MULTIPLE_CHOICES)).isFalse();
    }

    @Test
    public void statusMethodReturnHttpStatus() {
        assertThat(HttpStatusPredicate.of(HttpStatus.OK).status())
                .isEqualTo(HttpStatus.OK);
    }
}
