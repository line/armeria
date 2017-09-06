/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import com.linecorp.armeria.common.HttpStatus;

public class HttpResponseExceptionTest {
    @Test
    public void httpStatus() throws Exception {
        HttpResponseException exception = new HttpResponseException(HttpStatus.INTERNAL_SERVER_ERROR) {
            private static final long serialVersionUID = -1132103140930994783L;
        };
        assertThat(exception.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void onlyAcceptErrorHttpStatus() throws Exception {
        assertThatThrownBy(() -> new HttpResponseException(HttpStatus.ACCEPTED) {
            private static final long serialVersionUID = -1132103140930994783L;
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("(expected: a status that's neither informational, success nor redirection)");
    }
}
