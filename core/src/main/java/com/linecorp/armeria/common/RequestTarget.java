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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.DefaultRequestTarget;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * An HTTP request target, as defined in
 * <a href="https://datatracker.ietf.org/doc/html/rfc9112#section-3.2">Section 3.2, RFC 9112</a>.
 *
 * <p>Note: This interface doesn't support the
 * <a href="https://datatracker.ietf.org/doc/html/rfc9112#section-3.2.3">authority form</a>.
 */
@UnstableApi
public interface RequestTarget {

    /**
     * Returns a {@link RequestTarget} parsed and normalized from the specified request target string
     * in the context of server-side application. It rejects an absolute or authority form request target.
     * It also normalizes {@code '#'} into {@code '%2A'} instead of parsing a fragment.
     * Use {@link #forClient(String)} if you want to parse an absolute form request target or a fragment.
     *
     * @param reqTarget the request target string
     * @return a {@link RequestTarget} if parsed and normalized successfully, or {@code null} otherwise.
     */
    @Nullable
    static RequestTarget forServer(String reqTarget) {
        requireNonNull(reqTarget, "reqTarget");
        return DefaultRequestTarget.forServer(reqTarget, Flags.allowSemicolonInPathComponent(),
                                              Flags.allowDoubleDotsInQueryString());
    }

    /**
     * Returns a {@link RequestTarget} parsed and normalized from the specified request target string
     * in the context of client-side application. It rejects an authority form request target.
     *
     * @param reqTarget the request target string
     * @return a {@link RequestTarget} if parsed and normalized successfully, or {@code null} otherwise.
     * @see #forServer(String)
     */
    @Nullable
    static RequestTarget forClient(String reqTarget) {
        return forClient(reqTarget, null);
    }

    /**
     * Returns a {@link RequestTarget} parsed and normalized from the specified request target string
     * in the context of client-side application. It rejects an authority form request target.
     *
     * @param reqTarget the request target string
     * @param prefix the prefix to add to {@code reqTarget}. No prefix is added if {@code null} or empty.
     * @return a {@link RequestTarget} if parsed and normalized successfully, or {@code null} otherwise.
     * @see #forServer(String)
     */
    @Nullable
    static RequestTarget forClient(String reqTarget, @Nullable String prefix) {
        return DefaultRequestTarget.forClient(reqTarget, prefix);
    }

    /**
     * Returns the form of this {@link RequestTarget}.
     */
    RequestTargetForm form();

    /**
     * Returns the scheme of this {@link RequestTarget}.
     *
     * @return a non-empty string if {@link #form()} is {@link RequestTargetForm#ABSOLUTE}.
     *         {@code null} otherwise.
     */
    @Nullable
    String scheme();

    /**
     * Returns the authority of this {@link RequestTarget}.
     *
     * @return a non-empty string if {@link #form()} is {@link RequestTargetForm#ABSOLUTE}.
     *         {@code null} otherwise.
     */
    @Nullable
    String authority();

    /**
     * Returns the path of this {@link RequestTarget}, which always starts with {@code '/'}.
     */
    String path();

    /**
     * Returns the path of this {@link RequestTarget}, which always starts with {@code '/'}.
     * Unlike {@link #path()}, the returned string contains matrix variables it the original request path
     * contains them.
     *
     * @see <a href="https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/matrix-variables.html">
     *      Matrix Variables</a>
     */
    String maybePathWithMatrixVariables();

    /**
     * Returns the query of this {@link RequestTarget}.
     */
    @Nullable
    String query();

    /**
     * Returns the string that combines {@link #path()} and {@link #query()}.
     *
     * @return <code>{@link #path()} + '?' + {@link #query()}</code> if {@link #query()} is non-{@code null}.
     *         {@link #path()} if {@link #query()} is {@code null}.
     */
    default String pathAndQuery() {
        if (query() == null) {
            return path();
        }

        try (TemporaryThreadLocals tmp = TemporaryThreadLocals.acquire()) {
            return tmp.stringBuilder()
                      .append(path())
                      .append('?')
                      .append(query())
                      .toString();
        }
    }

    /**
     * Returns the fragment of this {@link RequestTarget}.
     */
    @Nullable
    String fragment();

    /**
     * Returns the string representation of this {@link RequestTarget}.
     *
     * @return One of the following:<ul>
     *           <li>An absolute URI if {@link #form()} is {@link RequestTargetForm#ABSOLUTE}, e.g.
     *               {@code "https://example.com/foo?bar#baz}</li>
     *           <li>Path with query and fragment if {@link #form()} is {@link RequestTargetForm#ORIGIN}, e.g.
     *               {@code "/foo?bar#baz"}</li>
     *           <li>{@code "*"} if {@link #form()} is {@link RequestTargetForm#ASTERISK}</li>
     *         </ul>
     */
    @Override
    String toString();
}
