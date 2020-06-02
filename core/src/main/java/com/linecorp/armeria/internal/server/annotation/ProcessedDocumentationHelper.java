/*
 * Copyright 2020 LINE Corporation
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

/**
 * Helper class for Documentation processing.
 */
public final class ProcessedDocumentationHelper {
    /**
     * Creates the file name used in the rest api documentation properties files.
     * @param className The class name used for generating the file name.
     * @return The used file name.
     */
    public static String getFileName(String className) {
        return "com.linecorp.armeria.docstrings.annotated." + className + ".properties";
    }

    private ProcessedDocumentationHelper() {}
}
