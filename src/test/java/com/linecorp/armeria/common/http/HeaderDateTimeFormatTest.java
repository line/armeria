/*
 * Copyright 2016 LINE Corporation
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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common.http;

import static com.linecorp.armeria.common.http.HeaderDateTimeFormat.format;
import static com.linecorp.armeria.common.http.HeaderDateTimeFormat.parse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.text.ParseException;
import java.time.Instant;

import org.junit.Test;

public class HeaderDateTimeFormatTest {
    /**
     * This date is set at "06 Nov 1994 08:49:37 GMT" (same used in example in
     * RFC documentation)
     * <p>
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html
     */
    private static final Instant DATE = Instant.ofEpochMilli(784111777000L);

    @Test
    public void testParse() throws ParseException {
        final Instant parsedDateWithSingleDigitDay = parse("Sun, 6 Nov 1994 08:49:37 GMT");
        assertEquals(DATE, parsedDateWithSingleDigitDay);

        final Instant parsedDateWithDoubleDigitDay = parse("Sun, 06 Nov 1994 08:49:37 GMT");
        assertEquals(DATE, parsedDateWithDoubleDigitDay);

        final Instant parsedDateWithDashSeparatorSingleDigitDay = parse("Sunday, 06-Nov-94 08:49:37 GMT");
        assertEquals(DATE, parsedDateWithDashSeparatorSingleDigitDay);

        final Instant parsedDateWithSingleDoubleDigitDay = parse("Sunday, 6-Nov-94 08:49:37 GMT");
        assertEquals(DATE, parsedDateWithSingleDoubleDigitDay);

        final Instant parsedDateWithoutGMT = parse("Sun Nov 6 08:49:37 1994");
        assertEquals(DATE, parsedDateWithoutGMT);
    }

    @Test
    public void testFormat() {
        final String formatted = format(DATE.toEpochMilli());
        assertNotNull(formatted);
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", formatted);
    }
}
