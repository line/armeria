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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

public class ResponseHeadersJsonDeserializerTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void responseHeaders() throws IOException {
        assertThat(mapper.readValue("{\":status\":\"200\"}", ResponseHeaders.class))
                .isEqualTo(ResponseHeaders.of(200));
    }

    @Test
    public void nonResponseHeaders() throws IOException {
        assertThatThrownBy(() -> mapper.readValue("{\"a\":\"b\"}", ResponseHeaders.class))
                .isInstanceOf(MismatchedInputException.class);
    }
}
