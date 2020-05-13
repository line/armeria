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
package com.linecorp.armeria.common;

import static com.linecorp.armeria.internal.common.PercentDecoder.decodeComponent;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

final class QueryStringDecoder {

    // Forked from netty-4.1.43.
    // https://github.com/netty/netty/blob/7d6d953153697bd66c3b01ca8ec73c4494a81788/codec-http/src/main/java/io/netty/handler/codec/http/QueryStringDecoder.java

    @SuppressWarnings("checkstyle:FallThrough")
    static QueryParams decodeParams(TemporaryThreadLocals tempThreadLocals,
                                    String s, int paramsLimit, boolean semicolonAsSeparator) {
        final QueryParamsBuilder params = QueryParams.builder();
        final int len = s.length();
        int nameStart = 0;
        int valueStart = 0;
        int i;
        loop:
        for (i = 0; i < len; i++) {
            switch (s.charAt(i)) {
                case '=':
                    if (valueStart == 0) {
                        valueStart = i + 1;
                    }
                    break;
                case ';':
                    if (!semicolonAsSeparator) {
                        continue;
                    }
                    // fall-through
                case '&':
                    if (addParam(tempThreadLocals, params, s, nameStart, valueStart, i) &&
                        --paramsLimit == 0) {
                        // TODO(trustin): Tell a user that some parameters were skipped.
                        return params.build();
                    }

                    nameStart = i + 1;
                    valueStart = 0;
                    break;
                case '#':
                    break loop;
                default:
                    // continue
            }
        }

        addParam(tempThreadLocals, params, s, nameStart, valueStart, i);

        return params.build();
    }

    private static boolean addParam(TemporaryThreadLocals tempThreadLocals,
                                    QueryParamsBuilder params,
                                    String s, int nameStart, int valueStart, int end) {
        if (nameStart == end) {
            return false;
        }

        final String name;
        final String value;
        if (valueStart == 0) {
            name = decodeComponent(tempThreadLocals, s, nameStart, end);
            value = "";
        } else {
            name = decodeComponent(tempThreadLocals, s, nameStart, valueStart - 1);
            value = decodeComponent(tempThreadLocals, s, valueStart, end);
        }

        params.add(name, value);
        return true;
    }

    private QueryStringDecoder() {}
}
