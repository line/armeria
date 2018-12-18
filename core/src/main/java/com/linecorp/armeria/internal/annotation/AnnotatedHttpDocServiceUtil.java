/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.internal.annotation;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.docs.DocService;

/**
 * A utility class to provide annotation processing in {@link AnnotatedHttpService} to build a
 * {@link DocService}.
 */
final class AnnotatedHttpDocServiceUtil {

    @Nullable
    static String getNormalizedTriePath(PathMapping pathMapping) {
        requireNonNull(pathMapping, "pathMapping");
        final Optional<String> triePath = pathMapping.triePath();

        if (!triePath.isPresent()) {
            return null;
        }

        final String path = triePath.get();
        int beginIndex = 0;

        final StringBuilder sb = new StringBuilder();
        for (String paramName : pathMapping.paramNames()) {
            final int colonIndex = path.indexOf(':', beginIndex);
            assert colonIndex != -1;
            sb.append(path, beginIndex, colonIndex);
            sb.append('{');
            sb.append(paramName);
            sb.append('}');
            beginIndex = colonIndex + 1;
        }
        if (beginIndex < path.length()) {
            sb.append(path, beginIndex, path.length());
        }
        return sb.toString();
    }

    private AnnotatedHttpDocServiceUtil() {}
}
