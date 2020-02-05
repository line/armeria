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

package com.linecorp.armeria.internal.server.annotation;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.annotation.Default;

/**
 * Holds the default values used in annotation attributes.
 */
public final class DefaultValues {

    /**
     * A string constant defining unspecified values from users.
     *
     * @see Default#value()
     */
    public static final String UNSPECIFIED = "\n\t\t\n\t\t\n\000\001\002\n\t\t\t\t\n";

    /**
     * Returns whether the specified value is specified by a user.
     */
    public static boolean isSpecified(@Nullable String value) {
        return !UNSPECIFIED.equals(value);
    }

    /**
     * Returns whether the specified value is not specified by a user.
     */
    public static boolean isUnspecified(@Nullable String value) {
        return UNSPECIFIED.equals(value);
    }

    /**
     * Returns the specified value if it is specified by a user.
     */
    @Nullable
    public static String getSpecifiedValue(@Nullable String value) {
        return isSpecified(value) ? value : null;
    }

    private DefaultValues() {}
}
