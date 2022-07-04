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

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.MediaType;

class MultipartsTest {

    @Test
    void boundary() {
        final String boundary = "foo";
        final MediaType multipartType = MediaType.MULTIPART_FORM_DATA.withParameter("boundary", boundary);
        assertThat(Multiparts.getBoundary(multipartType)).isEqualTo(boundary);

        assertThatThrownBy(() -> Multiparts.getBoundary(MediaType.MULTIPART_FORM_DATA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boundary parameter is missing on the Content-Type header");

        assertThatThrownBy(() -> Multiparts.getBoundary(MediaType.JSON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: multipart content type)");
    }
}
