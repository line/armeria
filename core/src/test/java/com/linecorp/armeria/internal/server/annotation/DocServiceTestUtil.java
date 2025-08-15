/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law of an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.server.annotation;

import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;

/**
 * A test utility class for DocService related tests.
 * This class resides in the same package as internal classes to provide access for testing purposes.
 */
public final class DocServiceTestUtil {

    /**
     * Creates a new instance of the package-private {@link DefaultDescriptiveTypeInfoProvider}.
     */
    public static DescriptiveTypeInfoProvider newDefaultDescriptiveTypeInfoProvider(boolean request) {
        return new DefaultDescriptiveTypeInfoProvider(request);
    }

    private DocServiceTestUtil() {}
}
