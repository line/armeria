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
package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.findAuthority;
import static io.netty.util.internal.StringUtil.decodeHexNibble;
import static java.util.Objects.requireNonNull;

import java.util.BitSet;
import java.util.Objects;

import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import it.unimi.dsi.fastutil.Arrays;
import it.unimi.dsi.fastutil.bytes.ByteArrays;

public final class DefaultRequestTarget implements RequestTarget {

    private static final String ALLOWED_COMMON_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?@!$&'()*,;=";

    /**
     * The lookup table for the characters allowed in a path.
     */
    private static final BitSet PATH_ALLOWED = toBitSet(ALLOWED_COMMON_CHARS + '+');

    /**
     * The lookup table for the characters allowed in a query.
     */
    private static final BitSet QUERY_ALLOWED = toBitSet(ALLOWED_COMMON_CHARS + "[]");

    /**
     * The lookup table for the characters allowed in a fragment.
     */
    private static final BitSet FRAGMENT_ALLOWED = PATH_ALLOWED;

    /**
     * The lookup table for the characters that whose percent encoding must be preserved
     * when used in a path because whether they are percent-encoded or not affects
     * their semantics. We do not normalize '%2F' and '%2f' in the path to '/' for compatibility with
     * other implementations in the ecosystem, e.g. HTTP/JSON to gRPC transcoding. See
     * <a href="https://github.com/googleapis/googleapis/blob/02710fa0ea5312d79d7fb986c9c9823fb41049a9/google/api/http.proto#L257-L258">http.proto</a>.
     */
    private static final BitSet PATH_MUST_PRESERVE_ENCODING = toBitSet("/?");

    /**
     * The lookup table for the characters that whose percent encoding must be preserved
     * when used in a query because whether they are percent-encoded or not affects
     * their semantics. For example, 'A%3dB=1' should NOT be normalized into 'A=B=1' because
     * 'A=B=1` means 'A' is 'B=1' but 'A%3dB=1' means 'A=B' is '1'.
     */
    private static final BitSet QUERY_MUST_PRESERVE_ENCODING = toBitSet(":/?[]@!$&'()*+,;=");

    /**
     * The lookup table for the characters that whose percent encoding must be preserved when used
     * in a fragment. We currently use the same table with {@link #PATH_MUST_PRESERVE_ENCODING}.
     */
    private static final BitSet FRAGMENT_MUST_PRESERVE_ENCODING = PATH_MUST_PRESERVE_ENCODING;

    private static BitSet toBitSet(String chars) {
        final BitSet bitSet = new BitSet();
        for (int i = 0; i < chars.length(); i++) {
            bitSet.set(chars.charAt(i));
        }
        return bitSet;
    }

    private enum ComponentType {
        CLIENT_PATH(PATH_ALLOWED, PATH_MUST_PRESERVE_ENCODING),
        SERVER_PATH(PATH_ALLOWED, PATH_MUST_PRESERVE_ENCODING),
        QUERY(QUERY_ALLOWED, QUERY_MUST_PRESERVE_ENCODING),
        FRAGMENT(FRAGMENT_ALLOWED, FRAGMENT_MUST_PRESERVE_ENCODING);

        private final BitSet allowed;
        private final BitSet mustPreserveEncoding;

        ComponentType(BitSet allowed, BitSet mustPreserveEncoding) {
            this.allowed = allowed;
            this.mustPreserveEncoding = mustPreserveEncoding;
        }

        boolean isAllowed(int cp) {
            return allowed.get(cp);
        }

        boolean mustPreserveEncoding(int cp) {
            return mustPreserveEncoding.get(cp);
        }
    }

    /**
     * The table that converts a byte into a percent-encoded chars, e.g. 'A' -> "%41".
     */
    private static final char[][] TO_PERCENT_ENCODED_CHARS = new char[256][];

    static {
        for (int i = 0; i < TO_PERCENT_ENCODED_CHARS.length; i++) {
            TO_PERCENT_ENCODED_CHARS[i] = String.format("%%%02X", i).toCharArray();
        }
    }

    private static final Bytes EMPTY_BYTES = new Bytes(0);
    private static final Bytes SLASH_BYTES = new Bytes(new byte[] { '/' });

    private static final RequestTarget INSTANCE_ASTERISK = createWithoutValidation(
            RequestTargetForm.ASTERISK,
            null,
            null,
            null,
            -1,
            "*",
            "*",
            null,
            null);

    /**
     * The main implementation of {@link RequestTarget#forServer(String)}.
     */
    @Nullable
    public static RequestTarget forServer(String reqTarget, boolean allowSemicolonInPathComponent,
                                          boolean allowDoubleDotsInQueryString) {
        final RequestTarget cached = RequestTargetCache.getForServer(reqTarget);
        if (cached != null) {
            return cached;
        }

        return slowForServer(reqTarget, allowSemicolonInPathComponent, allowDoubleDotsInQueryString);
    }

    /**
     * The main implementation of {@link RequestTarget#forClient(String, String)}.
     */
    @Nullable
    public static RequestTarget forClient(String reqTarget, @Nullable String prefix) {
        requireNonNull(reqTarget, "reqTarget");

        final int authorityPos = findAuthority(reqTarget);
        if (authorityPos >= 0) {
            // Note: For an absolute URI, we don't use `prefix` at all,
            //       so we can just use `reqTarget` in verbatim as a cache key.
            final RequestTarget cached = RequestTargetCache.getForClient(reqTarget);
            if (cached != null) {
                return cached;
            }

            // reqTarget is an absolute URI with scheme and authority.
            return slowAbsoluteFormForClient(reqTarget, authorityPos);
        }

        // Concatenate `prefix` and `reqTarget` if necessary.
        final String actualReqTarget;
        if (prefix == null || "*".equals(reqTarget)) {
            // No prefix was given or request target is `*`.
            actualReqTarget = reqTarget;
        } else {
            actualReqTarget = ArmeriaHttpUtil.concatPaths(prefix, reqTarget);
        }

        final RequestTarget cached = RequestTargetCache.getForClient(actualReqTarget);
        if (cached != null) {
            return cached;
        }

        // reqTarget is not an absolute URI; split by the first '?'.
        return slowForClient(actualReqTarget, null, 0);
    }

    /**
     * (Advanced users only) Returns a newly created {@link RequestTarget} filled with the specified
     * properties without any validation.
     */
    public static RequestTarget createWithoutValidation(
            RequestTargetForm form, @Nullable String scheme, @Nullable String authority,
            @Nullable String host, int port, String path, String pathWithMatrixVariables,
            @Nullable String query, @Nullable String fragment) {
        return new DefaultRequestTarget(
                form, scheme, authority, host, port, path, pathWithMatrixVariables, query, fragment);
    }

    private final RequestTargetForm form;
    @Nullable
    private final String scheme;
    @Nullable
    private final String authority;
    @Nullable
    private final String host;
    private final int port;
    private final String path;
    private final String maybePathWithMatrixVariables;
    @Nullable
    private final String query;
    @Nullable
    private final String fragment;
    private boolean cached;

    private DefaultRequestTarget(RequestTargetForm form, @Nullable String scheme,
                                 @Nullable String authority, @Nullable String host, int port,
                                 String path, String maybePathWithMatrixVariables,
                                 @Nullable String query, @Nullable String fragment) {

        assert (scheme != null && authority != null && host != null) ||
               (scheme == null && authority == null && host == null)
                : "scheme: " + scheme + ", authority: " + authority + ", host: " + host;

        this.form = form;
        this.scheme = scheme;
        this.authority = authority;
        this.host = host;
        this.port = port;
        this.path = path;
        this.maybePathWithMatrixVariables = maybePathWithMatrixVariables;
        this.query = query;
        this.fragment = fragment;
    }

    @Override
    public RequestTargetForm form() {
        return form;
    }

    @Override
    public String scheme() {
        return scheme;
    }

    @Override
    public String authority() {
        return authority;
    }

    @Override
    @Nullable
    public String host() {
        return host;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String maybePathWithMatrixVariables() {
        return maybePathWithMatrixVariables;
    }

    @Override
    public String query() {
        return query;
    }

    @Override
    public String fragment() {
        return fragment;
    }

    /**
     * Returns {@code true} if this {@link RequestTarget} is already stored in {@link RequestTargetCache}.
     */
    public boolean isCached() {
        return cached;
    }

    /**
     * Marks this {@link RequestTarget} as stored in {@link RequestTargetCache} so that it doesn't
     * try to store again.
     */
    public void setCached() {
        cached = true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof DefaultRequestTarget)) {
            return false;
        }

        final DefaultRequestTarget that = (DefaultRequestTarget) o;
        return path.equals(that.path) &&
               Objects.equals(query, that.query) &&
               Objects.equals(fragment, that.fragment) &&
               Objects.equals(authority, that.authority) &&
               Objects.equals(scheme, that.scheme);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, authority, path, query, fragment);
    }

    @Override
    public String toString() {
        try (TemporaryThreadLocals tmp = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tmp.stringBuilder();
            if (scheme != null) {
                buf.append(scheme).append("://").append(authority);
            }
            buf.append(path);
            if (query != null) {
                buf.append('?').append(query);
            }
            if (fragment != null) {
                buf.append('#').append(fragment);
            }
            return buf.toString();
        }
    }

    @Nullable
    private static RequestTarget slowForServer(String reqTarget, boolean allowSemicolonInPathComponent,
                                               boolean allowDoubleDotsInQueryString) {
        final Bytes path;
        final Bytes query;

        // Split by the first '?'.
        final int queryPos = reqTarget.indexOf('?');
        if (queryPos >= 0) {
            if ((path = decodePercentsAndEncodeToUtf8(
                    reqTarget, 0, queryPos,
                    ComponentType.SERVER_PATH, null, allowSemicolonInPathComponent)) == null) {
                return null;
            }
            if ((query = decodePercentsAndEncodeToUtf8(
                    reqTarget, queryPos + 1, reqTarget.length(),
                    ComponentType.QUERY, EMPTY_BYTES, true)) == null) {
                return null;
            }
        } else {
            if ((path = decodePercentsAndEncodeToUtf8(
                    reqTarget, 0, reqTarget.length(),
                    ComponentType.SERVER_PATH, null, allowSemicolonInPathComponent)) == null) {
                return null;
            }
            query = null;
        }

        // Reject a relative path and accept an asterisk (e.g. OPTIONS * HTTP/1.1).
        if (isRelativePath(path)) {
            if (query == null && path.length == 1 && path.data[0] == '*') {
                return INSTANCE_ASTERISK;
            } else {
                // Do not accept a relative path.
                return null;
            }
        }

        // Reject the prohibited patterns.
        if (pathContainsDoubleDots(path, allowSemicolonInPathComponent)) {
            return null;
        }
        if (!allowDoubleDotsInQueryString && queryContainsDoubleDots(query)) {
            return null;
        }

        final String encodedPath = encodePathToPercents(path);
        final String matrixVariablesRemovedPath;
        if (allowSemicolonInPathComponent) {
            matrixVariablesRemovedPath = encodedPath;
        } else {
            matrixVariablesRemovedPath = removeMatrixVariables(encodedPath);
            if (matrixVariablesRemovedPath == null) {
                return null;
            }
        }
        return new DefaultRequestTarget(RequestTargetForm.ORIGIN,
                                        null,
                                        null,
                                        null,
                                        -1,
                                        matrixVariablesRemovedPath,
                                        encodedPath,
                                        encodeQueryToPercents(query),
                                        null);
    }

    @Nullable
    public static String removeMatrixVariables(String path) {
        int semicolonIndex = path.indexOf(';');
        if (semicolonIndex < 0) {
            return path;
        }
        if (semicolonIndex == 0 || path.charAt(semicolonIndex - 1) == '/') {
            // Invalid matrix variable e.g. /;a=b/foo
            return null;
        }
        int subStringStartIndex = 0;
        try (TemporaryThreadLocals threadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder sb = threadLocals.stringBuilder();
            for (;;) {
                sb.append(path, subStringStartIndex, semicolonIndex);
                final int slashIndex = path.indexOf('/', semicolonIndex + 1);
                if (slashIndex < 0) {
                    return sb.toString();
                }
                subStringStartIndex = slashIndex;
                semicolonIndex = path.indexOf(';', subStringStartIndex + 1);
                if (semicolonIndex < 0) {
                    sb.append(path, subStringStartIndex, path.length());
                    return sb.toString();
                }
                if (path.charAt(semicolonIndex - 1) == '/') {
                    // Invalid matrix variable e.g. /prefix/;a=b/foo
                    return null;
                }
            }
        }
    }

    @Nullable
    private static RequestTarget slowAbsoluteFormForClient(String reqTarget, int authorityPos) {
        // Extract scheme and authority while looking for path.
        final SchemeAndAuthority schemeAndAuthority;
        final String scheme = reqTarget.substring(0, authorityPos - 3);
        final int nextPos = findNextComponent(reqTarget, authorityPos);
        final String authority;
        if (nextPos < 0) {
            // Found no other components after authority
            authority = reqTarget.substring(authorityPos);
        } else {
            // Path, query or fragment exists.
            authority = reqTarget.substring(authorityPos, nextPos);
        }

        // Reject a URI with an empty authority.
        if (authority.isEmpty()) {
            return null;
        }

        try {
            // Normalize scheme and authority.
            schemeAndAuthority = SchemeAndAuthority.of(scheme, authority);
        } catch (Exception ignored) {
            // Invalid scheme or authority.
            return null;
        }

        if (nextPos < 0) {
            return newAbsoluteTarget(schemeAndAuthority, "/", null, null);
        }

        return slowForClient(reqTarget, schemeAndAuthority, nextPos);
    }

    private static int findNextComponent(String reqTarget, int startPos) {
        for (int i = startPos; i < reqTarget.length(); i++) {
            switch (reqTarget.charAt(i)) {
                case '/':
                case '?':
                case '#':
                    return i;
            }
        }

        return -1;
    }

    @Nullable
    private static RequestTarget slowForClient(String reqTarget,
                                               @Nullable SchemeAndAuthority schemeAndAuthority,
                                               int pathPos) {
        final Bytes fragment;
        final Bytes path;
        final Bytes query;
        // Find where a query string and a fragment starts.
        final int queryPos;
        final int fragmentPos;
        // Note: We don't start from `pathPos + 1` but from `pathPos` just in case path is empty.
        final int maybeQueryPos = reqTarget.indexOf('?', pathPos);
        final int maybeFragmentPos = reqTarget.indexOf('#', pathPos);
        if (maybeQueryPos >= 0) {
            // Found '?'.
            if (maybeFragmentPos >= 0) {
                // Found '#', too.
                fragmentPos = maybeFragmentPos;
                if (maybeQueryPos < maybeFragmentPos) {
                    // '#' appeared after '?', e.g. ?foo#bar
                    queryPos = maybeQueryPos;
                } else {
                    // '#' appeared before '?', e.g. #foo?bar.
                    // It means the '?' we found is not a part of query string.
                    queryPos = -1;
                }
            } else {
                // No '#' in reqTarget.
                queryPos = maybeQueryPos;
                fragmentPos = -1;
            }
        } else {
            // No '?'.
            queryPos = -1;
            fragmentPos = maybeFragmentPos;
        }

        // Split into path, query and fragment.
        if (queryPos >= 0) {
            if ((path = decodePercentsAndEncodeToUtf8(
                    reqTarget, pathPos, queryPos,
                    ComponentType.CLIENT_PATH, SLASH_BYTES, true)) == null) {
                return null;
            }

            if (fragmentPos >= 0) {
                // path?query#fragment
                if ((query = decodePercentsAndEncodeToUtf8(
                        reqTarget, queryPos + 1, fragmentPos,
                        ComponentType.QUERY, EMPTY_BYTES, true)) == null) {
                    return null;
                }
                if ((fragment = decodePercentsAndEncodeToUtf8(
                        reqTarget, fragmentPos + 1, reqTarget.length(),
                        ComponentType.FRAGMENT, EMPTY_BYTES, true)) == null) {
                    return null;
                }
            } else {
                // path?query
                if ((query = decodePercentsAndEncodeToUtf8(
                        reqTarget, queryPos + 1, reqTarget.length(),
                        ComponentType.QUERY, EMPTY_BYTES, true)) == null) {
                    return null;
                }
                fragment = null;
            }
        } else {
            if (fragmentPos >= 0) {
                // path#fragment
                if ((path = decodePercentsAndEncodeToUtf8(
                        reqTarget, pathPos, fragmentPos,
                        ComponentType.CLIENT_PATH, EMPTY_BYTES, true)) == null) {
                    return null;
                }
                query = null;
                if ((fragment = decodePercentsAndEncodeToUtf8(
                        reqTarget, fragmentPos + 1, reqTarget.length(),
                        ComponentType.FRAGMENT, EMPTY_BYTES, true)) == null) {
                    return null;
                }
            } else {
                // path
                if ((path = decodePercentsAndEncodeToUtf8(
                        reqTarget, pathPos, reqTarget.length(),
                        ComponentType.CLIENT_PATH, EMPTY_BYTES, true)) == null) {
                    return null;
                }
                query = null;
                fragment = null;
            }
        }

        // Accept an asterisk (e.g. OPTIONS * HTTP/1.1).
        if (query == null && path.length == 1 && path.data[0] == '*') {
            return INSTANCE_ASTERISK;
        }

        final String encodedPath;
        if (isRelativePath(path)) {
            // Turn a relative path into an absolute one.
            encodedPath = '/' + encodePathToPercents(path);
        } else {
            encodedPath = encodePathToPercents(path);
        }

        final String encodedQuery = encodeQueryToPercents(query);
        final String encodedFragment = encodeFragmentToPercents(fragment);

        if (schemeAndAuthority != null) {
            return newAbsoluteTarget(schemeAndAuthority, encodedPath, encodedQuery, encodedFragment);
        } else {
            return new DefaultRequestTarget(RequestTargetForm.ORIGIN,
                                            null,
                                            null,
                                            null,
                                            -1,
                                            encodedPath,
                                            encodedPath, encodedQuery,
                                            encodedFragment);
        }
    }

    private static DefaultRequestTarget newAbsoluteTarget(
            SchemeAndAuthority schemeAndAuthority, String encodedPath,
            @Nullable String encodedQuery, @Nullable String encodedFragment) {

        final String scheme = schemeAndAuthority.scheme();
        final String maybeAuthority = schemeAndAuthority.authority();
        final String maybeHost = schemeAndAuthority.host();
        final int maybePort = schemeAndAuthority.port();
        final String authority;
        final String host;
        final int port;
        if (maybeHost == null) {
            authority = maybeAuthority;
            host = maybeAuthority;
            port = -1;
        } else {
            host = maybeHost;

            // Specify the port number only when necessary, so that https://foo/ and https://foo:443/
            // are considered equal.
            if (maybePort >= 0) {
                final Scheme parsedScheme = Scheme.tryParse(scheme);
                if (parsedScheme == null || parsedScheme.sessionProtocol().defaultPort() != maybePort) {
                    authority = maybeAuthority;
                    port = maybePort;
                } else {
                    authority = maybeHost;
                    port = -1;
                }
            } else {
                authority = maybeHost;
                port = -1;
            }
        }

        return new DefaultRequestTarget(RequestTargetForm.ABSOLUTE,
                                        scheme,
                                        authority,
                                        host,
                                        port,
                                        encodedPath,
                                        encodedPath,
                                        encodedQuery,
                                        encodedFragment);
    }

    private static boolean isRelativePath(Bytes path) {
        return path.length == 0 || path.data[0] != '/' || path.isEncoded(0);
    }

    @Nullable
    private static Bytes decodePercentsAndEncodeToUtf8(String value, int start, int end,
                                                       ComponentType type, @Nullable Bytes whenEmpty,
                                                       boolean allowSemicolonInPathComponent) {
        final int length = end - start;
        if (length == 0) {
            return whenEmpty;
        }

        final Bytes buf = new Bytes(Math.max(length * 3 / 2, 4));
        boolean wasSlash = false;
        for (final CodePointIterator i = new CodePointIterator(value, start, end);
             i.hasNextCodePoint();/* noop */) {
            final int pos = i.position();
            final int cp = i.nextCodePoint();

            if (cp == '%') {
                final int hexEnd = pos + 3;
                if (hexEnd > end) {
                    // '%' or '%x' (must be followed by two hexadigits)
                    return null;
                }

                final int digit1 = decodeHexNibble(value.charAt(pos + 1));
                final int digit2 = decodeHexNibble(value.charAt(pos + 2));
                if (digit1 < 0 || digit2 < 0) {
                    // The first or second digit is not hexadecimal.
                    return null;
                }

                final int decoded = (digit1 << 4) | digit2;
                if (type.mustPreserveEncoding(decoded) ||
                    (!allowSemicolonInPathComponent && decoded == ';')) {
                    buf.ensure(1);
                    buf.addEncoded((byte) decoded);
                    wasSlash = false;
                } else if (appendOneByte(buf, decoded, wasSlash, type)) {
                    wasSlash = decoded == '/';
                } else {
                    return null;
                }

                i.position(hexEnd);
                continue;
            }

            if (cp == '+' && type == ComponentType.QUERY) {
                buf.ensure(1);
                buf.addEncoded((byte) ' ');
                wasSlash = false;
                continue;
            }

            if (cp <= 0x7F) {
                if (!appendOneByte(buf, cp, wasSlash, type)) {
                    return null;
                }
                wasSlash = cp == '/';
                continue;
            }

            if (cp <= 0x7ff) {
                buf.ensure(2);
                buf.addEncoded((byte) ((cp >>> 6) | 0b110_00000));
                buf.addEncoded((byte) (cp & 0b111111 | 0b10_000000));
            } else if (cp <= 0xffff) {
                buf.ensure(3);
                buf.addEncoded((byte) ((cp >>> 12) | 0b1110_0000));
                buf.addEncoded((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) ((cp & 0b111111) | 0b10_000000));
            } else if (cp <= 0x1fffff) {
                buf.ensure(4);
                buf.addEncoded((byte) ((cp >>> 18) | 0b11110_000));
                buf.addEncoded((byte) (((cp >>> 12) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) ((cp & 0b111111) | 0b10_000000));
            } else if (cp <= 0x3ffffff) {
                // A valid unicode character will never reach here, but for completeness.
                // http://unicode.org/mail-arch/unicode-ml/Archives-Old/UML018/0330.html
                buf.ensure(5);
                buf.addEncoded((byte) ((cp >>> 24) | 0b111110_00));
                buf.addEncoded((byte) (((cp >>> 18) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) (((cp >>> 12) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) ((cp & 0b111111) | 0b10_000000));
            } else {
                // A valid unicode character will never reach here, but for completeness.
                // http://unicode.org/mail-arch/unicode-ml/Archives-Old/UML018/0330.html
                buf.ensure(6);
                buf.addEncoded((byte) ((cp >>> 30) | 0b1111110_0));
                buf.addEncoded((byte) (((cp >>> 24) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) (((cp >>> 18) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) (((cp >>> 12) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.addEncoded((byte) ((cp & 0b111111) | 0b10_000000));
            }

            wasSlash = false;
        }

        return buf;
    }

    private static boolean appendOneByte(Bytes buf, int cp, boolean wasSlash, ComponentType type) {
        if (cp == 0x7F) {
            // Reject the control character: 0x7F
            return false;
        }

        if (cp >>> 5 == 0) {
            // Reject the control characters: 0x00..0x1F
            if (type != ComponentType.QUERY) {
                return false;
            }

            if (cp != 0x0A && cp != 0x0D && cp != 0x09) {
                // .. except 0x0A (LF), 0x0D (CR) and 0x09 (TAB) because they are used in a form.
                return false;
            }
        }

        if (cp == '/' && type == ComponentType.SERVER_PATH) {
            if (!wasSlash) {
                buf.ensure(1);
                buf.add((byte) '/');
            } else {
                // Remove the consecutive slashes: '/path//with///consecutive////slashes'.
            }
        } else {
            buf.ensure(1);
            if (type.isAllowed(cp)) {
                buf.add((byte) cp);
            } else {
                buf.addEncoded((byte) cp);
            }
        }

        return true;
    }

    private static boolean pathContainsDoubleDots(Bytes path, boolean allowSemicolonInPathComponent) {
        final int length = path.length;
        byte b0 = 0;
        byte b1 = 0;
        byte b2 = '/';
        for (int i = 1; i < length; i++) {
            final byte b3 = path.data[i];
            // Flag if the last four bytes are `/../`.
            if (b1 == '.' && b2 == '.' && isSlash(b0) &&
                (isSlash(b3) || (!allowSemicolonInPathComponent && b3 == ';'))) {
                return true;
            }
            b0 = b1;
            b1 = b2;
            b2 = b3;
        }

        // Flag if the last three bytes are `/..`.
        return b1 == '.' && b2 == '.' && isSlash(b0);
    }

    private static boolean queryContainsDoubleDots(@Nullable Bytes query) {
        if (query == null) {
            return false;
        }

        final int length = query.length;
        boolean lookingForEquals = true;
        byte b0 = 0;
        byte b1 = 0;
        byte b2 = '/';
        for (int i = 0; i < length; i++) {
            byte b3 = query.data[i];

            // Treat the delimiters as `/` so that we can use isSlash() for matching them.
            switch (b3) {
                case '=':
                    // Treat only the first `=` as `/`, e.g.
                    // - `foo=..` and `foo=../` should be flagged.
                    // - `foo=..=` shouldn't be flagged because `..=` is not a relative path.
                    if (lookingForEquals) {
                        lookingForEquals = false;
                        b3 = '/';
                    }
                    break;
                case '&':
                case ';':
                    b3 = '/';
                    lookingForEquals = true;
                    break;
            }

            // Flag if the last four bytes are `/../` or `/..&`
            if (b1 == '.' && b2 == '.' && isSlash(b0) && isSlash(b3)) {
                return true;
            }

            b0 = b1;
            b1 = b2;
            b2 = b3;
        }

        return b1 == '.' && b2 == '.' && isSlash(b0);
    }

    private static boolean isSlash(byte b) {
        switch (b) {
            case '/':
            case '\\':
                return true;
            default:
                return false;
        }
    }

    private static String encodePathToPercents(Bytes value) {
        if (!value.hasEncodedBytes()) {
            // Deprecated, but it fits perfect for our use case.
            // noinspection deprecation
            return new String(value.data, 0, 0, value.length);
        }

        // Slow path: some percent-encoded chars.
        return slowEncodePathToPercents(value);
    }

    @Nullable
    private static String encodeQueryToPercents(@Nullable Bytes value) {
        if (value == null) {
            return null;
        }

        if (!value.hasEncodedBytes()) {
            // Deprecated, but it fits perfect for our use case.
            // noinspection deprecation
            return new String(value.data, 0, 0, value.length);
        }

        // Slow path: some percent-encoded chars.
        return slowEncodeQueryToPercents(value);
    }

    @Nullable
    private static String encodeFragmentToPercents(@Nullable Bytes value) {
        if (value == null) {
            return null;
        }

        if (!value.hasEncodedBytes()) {
            // Deprecated, but it fits perfect for our use case.
            // noinspection deprecation
            return new String(value.data, 0, 0, value.length);
        }

        // Slow path: some percent-encoded chars.
        return slowEncodePathToPercents(value);
    }

    private static String slowEncodePathToPercents(Bytes value) {
        final int length = value.length;
        final StringBuilder buf = new StringBuilder(length + value.numEncodedBytes() * 2);
        for (int i = 0; i < length; i++) {
            final int b = value.data[i] & 0xFF;

            if (value.isEncoded(i)) {
                buf.append(TO_PERCENT_ENCODED_CHARS[b]);
                continue;
            }

            buf.append((char) b);
        }

        return buf.toString();
    }

    private static String slowEncodeQueryToPercents(Bytes value) {
        final int length = value.length;
        final StringBuilder buf = new StringBuilder(length + value.numEncodedBytes() * 2);
        for (int i = 0; i < length; i++) {
            final int b = value.data[i] & 0xFF;

            if (value.isEncoded(i)) {
                if (b == ' ') {
                    buf.append('+');
                } else {
                    buf.append(TO_PERCENT_ENCODED_CHARS[b]);
                }
                continue;
            }

            buf.append((char) b);
        }

        return buf.toString();
    }

    private static final class Bytes {
        byte[] data;
        int length;
        @Nullable
        private BitSet encoded;
        private int numEncodedBytes;

        Bytes(int initialCapacity) {
            data = new byte[initialCapacity];
        }

        Bytes(byte[] data) {
            this.data = data;
            length = data.length;
        }

        void add(byte b) {
            data[length++] = b;
        }

        void addEncoded(byte b) {
            if (encoded == null) {
                encoded = new BitSet();
            }
            encoded.set(length);
            data[length++] = b;
            numEncodedBytes++;
        }

        boolean isEncoded(int index) {
            return encoded != null && encoded.get(index);
        }

        boolean hasEncodedBytes() {
            return encoded != null;
        }

        int numEncodedBytes() {
            return numEncodedBytes;
        }

        void ensure(int numBytes) {
            int newCapacity = length + numBytes;
            if (newCapacity <= data.length) {
                return;
            }

            newCapacity =
                    (int) Math.max(Math.min((long) data.length + (data.length >> 1), Arrays.MAX_ARRAY_SIZE),
                                   newCapacity);

            data = ByteArrays.forceCapacity(data, newCapacity, length);
        }
    }

    private static final class CodePointIterator {
        private final CharSequence str;
        private final int end;
        private int pos;

        CodePointIterator(CharSequence str, int start, int end) {
            this.str = str;
            this.end = end;
            pos = start;
        }

        int position() {
            return pos;
        }

        void position(int pos) {
            this.pos = pos;
        }

        boolean hasNextCodePoint() {
            return pos < end;
        }

        int nextCodePoint() {
            assert pos < end;

            final char c1 = str.charAt(pos++);
            if (Character.isHighSurrogate(c1) && pos < end) {
                final char c2 = str.charAt(pos);
                if (Character.isLowSurrogate(c2)) {
                    pos++;
                    return Character.toCodePoint(c1, c2);
                }
            }

            return c1;
        }
    }
}
