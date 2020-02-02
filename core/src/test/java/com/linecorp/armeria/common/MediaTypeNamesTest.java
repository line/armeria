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

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

import com.google.common.collect.FluentIterable;

/**
 * Test sync with MediaType and MediaTypeNames.
 */
class MediaTypeNamesTest {

    // reflection
    @Test
    void matchMediaTypeToMediaTypeNames() throws Exception {
        final FluentIterable<Field> mediaTypeFields = getConstantFields(MediaType.class);
        final FluentIterable<Field> mediaTypeNamesFields = getConstantFields(MediaTypeNames.class);

        final Map<String, MediaType> mediaTypeConstantsMap =
                getConstantFieldMap(mediaTypeFields, MediaType.class);
        final Map<String, String> mediaTypeNamesConstantsMap =
                getConstantFieldMap(mediaTypeNamesFields, String.class);

        for (Entry<String, MediaType> mediaTypeEntry : mediaTypeConstantsMap.entrySet()) {
            final String mediaTypeName = mediaTypeNamesConstantsMap.get(mediaTypeEntry.getKey());
            assertNotNull("MediaTypeName should be defined in MediaType constants", mediaTypeName);
            assertEquals(mediaTypeName, mediaTypeEntry.getValue().toString());
        }
    }

    // reflection
    @SuppressWarnings("Guava")
    private static <T> FluentIterable<Field> getConstantFields(Class<T> clazz) {
        return FluentIterable.from(asList(clazz.getDeclaredFields())).filter(input -> {
            final int modifiers = input.getModifiers();
            return isPublic(modifiers) &&
                   isStatic(modifiers) &&
                   isFinal(modifiers) &&
                   String.class.equals(input.getType());
        });
    }

    // reflection
    @SuppressWarnings("Guava")
    private static <T> Map<String, T> getConstantFieldMap(FluentIterable<Field> iterable, Class<T> clazz) {
        final Map<String, T> constantFieldMap = new HashMap<String, T>();
        iterable.stream().forEach(field -> {
            final String fieldName = field.getName();
            final T fieldValue;
            try {
                fieldValue = (T) field.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            constantFieldMap.put(fieldName, fieldValue);
        });
        return constantFieldMap;
    }
}
