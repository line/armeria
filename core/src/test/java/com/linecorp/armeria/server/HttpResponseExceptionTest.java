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

import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

public class HttpResponseExceptionTest {
    @Test
    public void testHttpResponse() throws Exception {
        final DefaultHttpResponse response = new DefaultHttpResponse();
        final HttpResponseException exception = HttpResponseException.of(response);
        response.write(HttpHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR)
                                  .add(HttpHeaderNames.CONTENT_TYPE,
                                       MediaType.PLAIN_TEXT_UTF_8.toString()));
        response.close();

        final AggregatedHttpMessage message = exception.httpResponse().aggregate().join();
        assertThat(message.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(message.headers().get(HttpHeaderNames.CONTENT_TYPE))
                .isEqualTo(MediaType.PLAIN_TEXT_UTF_8.toString());
    }
}
