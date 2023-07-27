/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

public final class MappedPathUtil {

    /**
     * Returns the path with its prefix removed. Unlike {@link ServiceRequestContext#mappedPath()}, this method
     * returns the path with matrix variables if the mapped path contains matrix variables.
     * This returns {@code null} if the path has matrix variables in the prefix.
     */
    @Nullable
    public static String mappedPath(ServiceRequestContext ctx) {
        final RequestTarget requestTarget = ctx.routingContext().requestTarget();
        final String pathWithMatrixVariables = requestTarget.maybePathWithMatrixVariables();
        if (pathWithMatrixVariables.equals(ctx.path())) {
            return ctx.mappedPath();
        }
        // The request path contains matrix variables. e.g. "/foo/bar/users;name=alice"

        final String prefix = ctx.path().substring(0, ctx.path().length() - ctx.mappedPath().length());
        // The prefix is now `/foo/bar`
        if (!pathWithMatrixVariables.startsWith(prefix)) {
            // The request path has matrix variables in the wrong place. e.g. "/foo;name=alice/bar/users"
            return null;
        }
        final String mappedPath = pathWithMatrixVariables.substring(prefix.length());
        if (mappedPath.charAt(0) != '/') {
            // Again, the request path has matrix variables in the wrong place. e.g. "/foo/bar;/users"
            return null;
        }
        return mappedPath;
    }

    private MappedPathUtil() {}
}
