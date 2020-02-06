/*
 * Copyright 2019 LINE Corporation
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

import org.junit.jupiter.api.Test;

class HttpStatusTest {
    @Test
    void statusIsInformationIfStatusCode2XX() {
        assertThat(HttpStatus.valueOf(100).isInformational()).isTrue();
        assertThat(HttpStatus.valueOf(199).isInformational()).isTrue();
        assertThat(HttpStatus.valueOf(200).isInformational()).isFalse();
        assertThat(HttpStatus.valueOf(300).isInformational()).isFalse();
        assertThat(HttpStatus.valueOf(400).isInformational()).isFalse();
        assertThat(HttpStatus.valueOf(500).isInformational()).isFalse();
    }

    @Test
    void statusIsSuccessIfStatusCode2XX() {
        assertThat(HttpStatus.valueOf(100).isSuccess()).isFalse();
        assertThat(HttpStatus.valueOf(200).isSuccess()).isTrue();
        assertThat(HttpStatus.valueOf(299).isSuccess()).isTrue();
        assertThat(HttpStatus.valueOf(300).isSuccess()).isFalse();
        assertThat(HttpStatus.valueOf(400).isSuccess()).isFalse();
        assertThat(HttpStatus.valueOf(500).isSuccess()).isFalse();
    }

    @Test
    void statusIsRedirectionIfStatusCode3XX() {
        assertThat(HttpStatus.valueOf(100).isRedirection()).isFalse();
        assertThat(HttpStatus.valueOf(200).isRedirection()).isFalse();
        assertThat(HttpStatus.valueOf(300).isRedirection()).isTrue();
        assertThat(HttpStatus.valueOf(399).isRedirection()).isTrue();
        assertThat(HttpStatus.valueOf(400).isRedirection()).isFalse();
        assertThat(HttpStatus.valueOf(500).isRedirection()).isFalse();
    }

    @Test
    void statusIsErrorIfStatusCode4XX() {
        assertThat(HttpStatus.valueOf(100).isError()).isFalse();
        assertThat(HttpStatus.valueOf(200).isError()).isFalse();
        assertThat(HttpStatus.valueOf(300).isError()).isFalse();
        assertThat(HttpStatus.valueOf(400).isError()).isTrue();
        assertThat(HttpStatus.valueOf(499).isError()).isTrue();
    }

    @Test
    void statusIsErrorIfStatusCode5XX() {
        assertThat(HttpStatus.valueOf(100).isError()).isFalse();
        assertThat(HttpStatus.valueOf(200).isError()).isFalse();
        assertThat(HttpStatus.valueOf(300).isError()).isFalse();
        assertThat(HttpStatus.valueOf(500).isError()).isTrue();
        assertThat(HttpStatus.valueOf(599).isError()).isTrue();
    }
}
