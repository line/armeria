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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class QueryParamsTest {

    @Test
    void testSensitiveParamNames() throws Exception {
        final QueryParams params = QueryParams.of("param1", "value1",
                                                  "param1", "value2",
                                                  "Param1", "Value3",
                                                  "PARAM1", "VALUE4");

        assertThat(params.getAll("param1")).containsExactly("value1", "value2");
        assertThat(params.getAll("Param1")).containsExactly("Value3");
        assertThat(params.getAll("PARAM1")).containsExactly("VALUE4");

        assertThat(params.names())
                .containsExactlyInAnyOrder("param1", "Param1", "PARAM1");
    }

    @Test
    void testInvalidParamName() throws Exception {
        assertThatThrownBy(() -> QueryParams.of(null, "value1"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testSetObject() {
        final String expectedDate = "Mon, 3 Dec 2007 10:15:30 GMT";
        final Instant instant = Instant.parse("2007-12-03T10:15:30.00Z");
        final Date date = new Date(instant.toEpochMilli());
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(instant.toEpochMilli());

        final QueryParamsBuilder params = QueryParams.builder();
        params.setObject("date", date);
        params.setObject("instant", instant);
        params.setObject("calendar", calendar);
        params.setObject("cache-control", ServerCacheControl.DISABLED);
        params.setObject("media-type", MediaType.PLAIN_TEXT_UTF_8);

        assertThat(params.get("date")).isEqualTo(expectedDate);
        assertThat(params.get("instant")).isEqualTo(expectedDate);
        assertThat(params.get("calendar")).isEqualTo(expectedDate);
        assertThat(params.get("cache-control")).isEqualTo("no-cache, no-store, must-revalidate");
        assertThat(params.get("media-type")).isEqualTo("text/plain; charset=utf-8");
    }
}
