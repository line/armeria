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

public final class StringUtil {
    private static final int CACHE_SIZE = 2001;
    private static final int MAX_NUM = CACHE_SIZE / 2;
    private static final int MIN_NUM = -MAX_NUM;
    private static final String[] intToString = new String[CACHE_SIZE];

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

    private StringUtil() {}
}
