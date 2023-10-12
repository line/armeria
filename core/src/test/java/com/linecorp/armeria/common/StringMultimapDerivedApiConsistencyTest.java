/*
 * Copyright 2019 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Makes sure that {@link QueryParamGetters}, {@link QueryParamsBuilder}, {@link HttpHeaderGetters} and
 * {@link HttpHeadersBuilder} have a consistent set of getter and setter methods. This test prevents devs
 * from adding a new method to only one of them. Keep in mind that you must:
 * <ol>
 *   <li>Add a new method to {@link StringMultimap} and {@link StringMultimapGetters} first.</li>
 *   <li>Override the new method and add Javadoc in {@link QueryParamGetters} and
 *       {@link HttpHeaderGetters}.</li>
 *   <li>Add the new method's corresponding builder methods to both {@link QueryParamsBuilder} and
 *       {@link HttpHeadersBuilder}.</li>
 * </ol>
 */
class StringMultimapDerivedApiConsistencyTest {

    @Test
    void gettersApiConsistency() {
        final List<String> queryParamGettersMethods = signature(QueryParamGetters.class);
        final List<String> httpHeaderGettersMethods = signature(HttpHeaderGetters.class);
        assertThat(queryParamGettersMethods).isEqualTo(httpHeaderGettersMethods);
    }

    @Test
    void buildersApiConsistency() {
        final List<String> queryParamGettersMethods = signature(QueryParamsBuilder.class);
        final List<String> httpHeaderGettersMethods = signature(HttpHeadersBuilder.class);
        assertThat(queryParamGettersMethods).isEqualTo(httpHeaderGettersMethods);
    }

    private static List<String> signature(Class<?> type) {
        assertThat(type).isInterface();
        return Arrays.stream(type.getMethods())
                     .filter(m -> {
                         final String methodName = m.getName();
                         // Ignore the methods only available in HttpHeaderGetters or HttpHeadersBuilder.
                         if ("endOfStream".equals(methodName) ||
                             "isEndOfStream".equals(methodName) ||
                             "contentLength".equals(methodName) ||
                             "isContentLengthUnknown".equals(methodName) ||
                             "contentLengthUnknown".equals(methodName) ||
                             "contentType".equals(methodName) ||
                             "contentDisposition".equals(methodName)) {
                             return false;
                         }

                         // Ignore the methods only available in QueryParamGetters
                         if ("toQueryString".equals(methodName) ||
                             "appendQueryString".equals(methodName)) {
                             return false;
                         }

                         // Ignore the build() method.
                         return !"build".equals(methodName);
                     })
                     .map(StringMultimapDerivedApiConsistencyTest::signature)
                     .distinct()
                     .sorted()
                     .collect(toImmutableList());
    }

    /**
     * Generates the signature of the specified {@link Method}, normalizing inevitable differences between
     * {@link QueryParamGetters} and {@link HttpHeaderGetters} (or {@link QueryParamsBuilder} and
     * {@link HttpHeadersBuilder}).
     */
    private static String signature(Method method) {
        final StringBuilder buf = new StringBuilder();
        final String returnTypeName = method.getReturnType().getName();
        if (returnTypeName.endsWith("Builder")) {
            // Normalize the self return type.
            buf.append("SELF ");
        } else {
            buf.append(returnTypeName).append(' ');
        }
        buf.append(method.getName()).append('(');
        boolean hasParameters = false;
        for (Class<?> parameterType : method.getParameterTypes()) {
            // Normalize the input string type.
            if (parameterType == CharSequence.class) {
                parameterType = String.class;
            }
            buf.append(parameterType.getName());
            buf.append(", ");
            hasParameters = true;
        }
        if (hasParameters) {
            buf.setLength(buf.length() - 2);
        }
        buf.append(')');
        return buf.toString();
    }
}
