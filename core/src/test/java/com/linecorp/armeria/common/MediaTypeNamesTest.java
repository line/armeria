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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.MediaTypeTest.getConstantFields;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Test sync with MediaType and MediaTypeNames.
 */
class MediaTypeNamesTest {

    // reflection
    @Test
    void matchMediaTypeToMediaTypeNames() throws Exception {
        final Stream<Field> mediaTypeFields = getConstantFields(MediaType.class);
        final Stream<Field> mediaTypeNamesFields = getConstantFields(MediaTypeNames.class,
                                                                     String.class);

        final Map<String, MediaType> mediaTypeConstantsMap = getConstantFieldMap(mediaTypeFields);
        final Map<String, String> mediaTypeNamesConstantsMap = getConstantFieldMap(mediaTypeNamesFields);

        for (Entry<String, MediaType> mediaTypeEntry : mediaTypeConstantsMap.entrySet()) {
            final String mediaTypeName = mediaTypeNamesConstantsMap.get(mediaTypeEntry.getKey());
            assertThat(mediaTypeName).isEqualTo(mediaTypeEntry.getValue().toString());
        }
    }

    private static <T> Map<String, T> getConstantFieldMap(Stream<Field> stream) {
        return stream
                .collect(toImmutableMap(Field::getName, field -> {
                    try {
                        @SuppressWarnings("unchecked")
                        final T cast = (T) field.get(null);
                        return cast;
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }));
    }
}
