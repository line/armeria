/*
 *  Copyright 2020 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License; charset=utf-8"; you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Test sync with MediaType and MediaTypeNames
 */
class MediaTypeNamesTest {

    @Test
    void matchMediaTypeToMediaTypeNames() throws Exception {
        // ANY Type
        assertEquals(MediaTypeNames.ANY_TYPE, MediaType.ANY_TYPE.toString());

        // text type
        assertEquals(MediaTypeNames.EVENT_STREAM, MediaType.EVENT_STREAM.toString());
        assertEquals(MediaTypeNames.HTML_UTF_8, MediaType.HTML_UTF_8.toString());

        // image type
        assertEquals(MediaTypeNames.ICO, MediaType.ICO.toString());
        assertEquals(MediaTypeNames.SVG_UTF_8, MediaType.SVG_UTF_8.toString());

        // audio type
        assertEquals(MediaTypeNames.MP4_AUDIO, MediaType.MP4_AUDIO.toString());

        // video type
        assertEquals(MediaTypeNames.WMV, MediaType.WMV.toString());

        // application type
        assertEquals(MediaTypeNames.JOSE_JSON, MediaType.JOSE_JSON.toString());
        assertEquals(MediaTypeNames.JSON_UTF_8, MediaType.JSON_UTF_8.toString());
    }
}
