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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;

class HttpFileBuilderTest {
    @Test
    void shouldIgnoreStatusHeader() {
        final HttpFile file = HttpFile.builder(HttpData.ofUtf8("foo"))
                                      .setHeader(HttpHeaderNames.STATUS, 404)
                                      .build();

        assertThat(file.readHeaders(MoreExecutors.directExecutor()).join().status())
                .isEqualTo(HttpStatus.OK);
    }
}
