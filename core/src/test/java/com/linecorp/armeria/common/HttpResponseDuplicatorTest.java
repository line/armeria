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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static com.linecorp.armeria.common.HttpHeaderNames.VARY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class HttpResponseDuplicatorTest {

    @Test
    public void aggregateTwice() {
        final DefaultHttpResponse publisher = new DefaultHttpResponse();
        final HttpResponseDuplicator resDuplicator = new HttpResponseDuplicator(publisher);

        publisher.write(HttpHeaders.of(HttpStatus.OK).set(CONTENT_TYPE, "text/plain"));
        publisher.write(HttpData.ofUtf8("Armeria "));
        publisher.write(HttpData.ofUtf8("is "));
        publisher.write(HttpData.ofUtf8("awesome!"));
        publisher.close();

        final AggregatedHttpMessage res1 = resDuplicator.duplicateStream().aggregate().join();
        final AggregatedHttpMessage res2 = resDuplicator.duplicateStream().aggregate().join();

        assertThat(res1.status()).isEqualTo(HttpStatus.OK);
        assertThat(res1.headers().get(CONTENT_TYPE)).isEqualTo("text/plain");
        assertThat(res1.headers().get(VARY)).isNull();
        assertThat(res1.content().toStringUtf8()).isEqualTo("Armeria is awesome!");

        assertThat(res2.status()).isEqualTo(HttpStatus.OK);
        assertThat(res2.headers().get(CONTENT_TYPE)).isEqualTo("text/plain");
        assertThat(res2.headers().get(VARY)).isNull();
        assertThat(res2.content().toStringUtf8()).isEqualTo("Armeria is awesome!");
        resDuplicator.close();
    }
}
