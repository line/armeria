/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.internal.common.util;

import java.util.Map;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;

public final class StringUtil {
    private static final int MAX_NUM = 1000;
    private static final int MIN_NUM = -MAX_NUM;
    private static final String[] intToString = new String[MAX_NUM * 2 + 1];

    private static final Map<String, Boolean> stringToBoolean =
            ImmutableMap.<String, Boolean>builder()
                        .put("true", true)
                        .put("1", true)
                        .put("false", false)
                        .put("0", false)
                        .build();

    static {
        for (int i = MIN_NUM; i <= MAX_NUM; i++) {
            final String str = Integer.toString(i);
            intToString[i + MAX_NUM] = str;
        }
    }

    public static String toString(int num) {
        if (num >= MIN_NUM && num <= MAX_NUM) {
            return intToString[num + MAX_NUM];
        }
        return Integer.toString(num);
    }

    public static String toString(long num) {
        if (num >= MIN_NUM && num <= MAX_NUM) {
            return intToString[(int) (num + MAX_NUM)];
        }
        return Long.toString(num);
    }

    public static Boolean toBoolean(String s, boolean errorOnFailure) {
        final Boolean result = stringToBoolean.get(Ascii.toLowerCase(s));
        if (result != null) {
            return result;
        }
        if (errorOnFailure) {
            throw new IllegalArgumentException("must be one of " + stringToBoolean.keySet() + ": " + s);
        }
        return null;
    }

    private StringUtil() {}
}
