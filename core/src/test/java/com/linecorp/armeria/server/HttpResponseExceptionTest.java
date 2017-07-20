/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.HttpStatus;

public class HttpResponseExceptionTest {
    static {
        System.setProperty("com.linecorp.armeria.verboseExceptions", "false");
    }

    @Test
    public void fillInStackTrace() throws Exception {
        MyHttpResponseException exception = new MyHttpResponseException();
        assertThat(exception.getStackTrace()).isEmpty();
    }

    private static class MyHttpResponseException extends HttpResponseException {
        private static final long serialVersionUID = 3634745947516435390L;

        MyHttpResponseException() {
            super(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
