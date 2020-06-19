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

package com.linecorp.armeria.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

class TruncatingHttpResponseTest {

    @Test
    void bigContent() throws InterruptedException {
        final HttpResponseWriter writer = HttpResponse.streaming();
        writer.write(() -> ResponseHeaders.of(HttpStatus.OK));
        for (int i = 0; i < 4; i++) {
            writer.write(() -> HttpData.ofUtf8("1234567890"));
            writer.write(() -> HttpData.ofUtf8("ABCDEFGHIJ"));
        }
        writer.close();
        final TruncatingHttpResponse response = new TruncatingHttpResponse(writer, 20);
        final AggregatedHttpResponse agg = response.aggregate().join();
        assertThat(agg.contentUtf8()).isEqualTo("1234567890ABCDEFGHIJ");
    }

    @Test
    void smallContent() throws InterruptedException {
        final HttpResponseWriter writer = HttpResponse.streaming();
        writer.write(() -> ResponseHeaders.of(HttpStatus.OK));
        writer.write(() -> HttpData.ofUtf8("1234567890"));
        writer.close();
        final TruncatingHttpResponse response = new TruncatingHttpResponse(writer, 20);
        final AggregatedHttpResponse agg = response.aggregate().join();
        assertThat(agg.contentUtf8()).isEqualTo("1234567890");
    }
}
