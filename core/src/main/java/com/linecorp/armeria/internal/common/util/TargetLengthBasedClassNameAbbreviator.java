/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
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
/*
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package com.linecorp.armeria.internal.common.util;

public final class TargetLengthBasedClassNameAbbreviator {

    // Forked from https://github.com/qos-ch/logback/blob/c2dcbfc/logback-classic/src/main/java/ch/qos/logback/classic/pattern/TargetLengthBasedClassNameAbbreviator.java

    private static final int MAX_DOTS = 16;
    private final int targetLength;

    private static int computeDotIndexes(final String className, int[] dotArray) {
        int dotCount = 0;
        int k = 0;
        while (true) {
            // ignore the $ separator in our computations. This is both convenient
            // and sensible.
            k = className.indexOf('.', k);
            if (k != -1 && dotCount < MAX_DOTS) {
                dotArray[dotCount] = k;
                dotCount++;
                k++;
            } else {
                break;
            }
        }
        return dotCount;
    }

    public TargetLengthBasedClassNameAbbreviator(int targetLength) {
        this.targetLength = targetLength;
    }

    public String abbreviate(String fqClassName) {
        if (fqClassName == null) {
            throw new IllegalArgumentException("Class name may not be null");
        }

        final int inLen = fqClassName.length();
        if (inLen < targetLength) {
            return fqClassName;
        }

        // dotIndexesAndLength contains dotIndexesArray[MAX_DOTS] and lengthArray[MAX_DOTS + 1]
        // In case of lengthArray, a.b.c contains 2 dots but 2+1 parts.
        // see also http://jira.qos.ch/browse/LBCLASSIC-110
        // TODO(hexoul): Analyze whether int array of thread local can be used or not.
        final int[] dotIndexesAndLength = new int[MAX_DOTS * 2 + 1];
        final int dotCount = computeDotIndexes(fqClassName, dotIndexesAndLength);

        // if there are not dots than abbreviation is not possible
        if (dotCount == 0) {
            return fqClassName;
        }
        computeLengthArray(fqClassName, dotIndexesAndLength, dotCount);
        // TODO(hexoul): Analyze whether StringBuilder of thread local can be used or not.
        final StringBuilder buf = new StringBuilder(targetLength);
        for (int i = 0; i <= dotCount; i++) {
            if (i == 0) {
                buf.append(fqClassName, 0, dotIndexesAndLength[MAX_DOTS + i] - 1);
            } else {
                buf.append(fqClassName, dotIndexesAndLength[i - 1],
                           dotIndexesAndLength[i - 1] + dotIndexesAndLength[MAX_DOTS + i]);
            }
        }

        return buf.toString();
    }

    private void computeLengthArray(final String className, int[] dotIndexesAndLength, int dotCount) {
        int toTrim = className.length() - targetLength;
        int len;
        for (int i = 0; i < dotCount; i++) {
            int previousDotPosition = -1;
            if (i > 0) {
                previousDotPosition = dotIndexesAndLength[i - 1];
            }
            final int available = dotIndexesAndLength[i] - previousDotPosition - 1;

            len = (available < 1) ? available : 1;
            if (toTrim > 0) {
                len = (available < 1) ? available : 1;
            } else {
                len = available;
            }
            toTrim -= (available - len);
            dotIndexesAndLength[MAX_DOTS + i] = len + 1;
        }

        final int lastDotIndex = dotCount - 1;
        dotIndexesAndLength[MAX_DOTS + dotCount] = className.length() - dotIndexesAndLength[lastDotIndex];
    }
}
