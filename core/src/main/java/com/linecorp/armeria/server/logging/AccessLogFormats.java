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

package com.linecorp.armeria.server.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.logging.AccessLogComponent.ofDefaultRequestTimestamp;
import static com.linecorp.armeria.server.logging.AccessLogComponent.ofPredefinedCommon;
import static com.linecorp.armeria.server.logging.AccessLogComponent.ofQuotedRequestHeader;
import static com.linecorp.armeria.server.logging.AccessLogComponent.ofText;
import static com.linecorp.armeria.server.logging.AccessLogType.AUTHENTICATED_USER;
import static com.linecorp.armeria.server.logging.AccessLogType.REMOTE_HOST;
import static com.linecorp.armeria.server.logging.AccessLogType.REQUEST_LINE;
import static com.linecorp.armeria.server.logging.AccessLogType.RESPONSE_LENGTH;
import static com.linecorp.armeria.server.logging.AccessLogType.RESPONSE_STATUS_CODE;
import static com.linecorp.armeria.server.logging.AccessLogType.RFC931;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.logging.AccessLogComponent.AttributeComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.CommonComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.HttpHeaderComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.RequestLogComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.TextComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.TimestampComponent;
import com.linecorp.armeria.server.logging.AccessLogType.VariableRequirement;

/**
 * Pre-defined access log formats and the utility methods for {@link AccessLogComponent}.
 */
final class AccessLogFormats {

    private static final AccessLogComponent BLANK = ofText(" ");

    /**
     * A common log format.
     */
    static final List<AccessLogComponent> COMMON =
            ImmutableList.of(ofPredefinedCommon(REMOTE_HOST), BLANK,
                             ofPredefinedCommon(RFC931), BLANK,
                             ofPredefinedCommon(AUTHENTICATED_USER), BLANK,
                             ofDefaultRequestTimestamp(), BLANK,
                             ofPredefinedCommon(REQUEST_LINE), BLANK,
                             ofPredefinedCommon(RESPONSE_STATUS_CODE), BLANK,
                             ofPredefinedCommon(RESPONSE_LENGTH));

    /**
     * A combined log format.
     */
    static final List<AccessLogComponent> COMBINED =
            ImmutableList.of(ofPredefinedCommon(REMOTE_HOST), BLANK,
                             ofPredefinedCommon(RFC931), BLANK,
                             ofPredefinedCommon(AUTHENTICATED_USER), BLANK,
                             ofDefaultRequestTimestamp(), BLANK,
                             ofPredefinedCommon(REQUEST_LINE), BLANK,
                             ofPredefinedCommon(RESPONSE_STATUS_CODE), BLANK,
                             ofPredefinedCommon(RESPONSE_LENGTH), BLANK,
                             ofQuotedRequestHeader(HttpHeaderNames.REFERER), BLANK,
                             ofQuotedRequestHeader(HttpHeaderNames.USER_AGENT), BLANK,
                             ofQuotedRequestHeader(HttpHeaderNames.COOKIE));

    static List<AccessLogComponent> parseCustom(String formatStr) {
        requireNonNull(formatStr, "formatStr");
        final ImmutableList.Builder<AccessLogComponent> builder = ImmutableList.builder();

        final StringBuilder textBuilder = new StringBuilder();
        Condition.Builder condBuilder = null;
        String variable = null;

        State state = State.TEXT;
        for (int i = 0; i < formatStr.length();/* Increase 'i' at the end of the loop. */) {
            final char ch = formatStr.charAt(i);
            switch (state) {
                case TEXT:
                    if (ch == '%') {
                        if (textBuilder.length() > 0) {
                            builder.add(ofText(newStringAndReset(textBuilder)));
                        }
                        condBuilder = null;
                        variable = null;

                        state = State.PERCENT;
                    } else {
                        textBuilder.append(ch);
                    }
                    break;
                case PERCENT:
                    // Loop again.
                    if (Character.isAlphabetic(ch)) {
                        state = State.TOKEN;
                        continue;
                    }
                    // Loop again.
                    if (Character.isDigit(ch)) {
                        condBuilder = Condition.builder();
                        state = State.CONDITION;
                        continue;
                    }

                    if (ch == '!') {
                        condBuilder = Condition.builder().setSign(false);
                        state = State.CONDITION;
                    } else if (ch == '{') {
                        state = State.VARIABLE;
                    }
                    break;
                case CONDITION:
                    if (Character.isDigit(ch)) {
                        textBuilder.append(ch);
                    } else {
                        if (textBuilder.length() > 0) {
                            condBuilder.addHttpStatus(newStringAndReset(textBuilder));
                        }
                        // Loop again.
                        if (Character.isAlphabetic(ch)) {
                            state = State.TOKEN;
                            continue;
                        }
                        if (ch == '{') {
                            state = State.VARIABLE;
                        } else if (ch != ',') {
                            throw new IllegalArgumentException("Unexpected character in condition:" + ch);
                        }
                    }
                    break;
                case VARIABLE:
                    if (ch != '}') {
                        textBuilder.append(ch);
                    } else {
                        if (textBuilder.length() > 0) {
                            variable = newStringAndReset(textBuilder);
                        }
                        state = State.TOKEN;
                    }
                    break;
                case TOKEN:
                    builder.add(newAccessLogComponent(ch, variable, condBuilder));
                    state = State.TEXT;
                    break;
            }

            // Go to the next index only if the 'switch' statement exits by 'break' statement.
            ++i;
        }

        if (state != State.TEXT) {
            throw new IllegalArgumentException("Unexpected access log format: " + formatStr);
        }

        if (textBuilder.length() > 0) {
            builder.add(ofText(newStringAndReset(textBuilder)));
        }

        return builder.build();
    }

    private static AccessLogComponent newAccessLogComponent(char token,
                                                            @Nullable String variable,
                                                            @Nullable Condition.Builder condBuilder) {
        final AccessLogType type = AccessLogType.find(token);
        checkArgument(type != null, "Unexpected token character: '%s'", token);
        if (type.variableRequirement() == VariableRequirement.YES) {
            checkArgument(variable != null,
                          "Token %s requires a variable.", type.token());
        }
        if (type.isConditionAvailable()) {
            if (condBuilder != null) {
                checkArgument(!condBuilder.isEmpty(),
                              "Token %s has an invalid condition.", type.token());
            }
        } else {
            checkArgument(condBuilder == null,
                          "Token %s does not support a condition.", type.token());
        }

        if (TextComponent.isSupported(type)) {
            assert variable != null;
            return ofText(variable);
        }

        // Do not add quotes when parsing a user-provided custom format.
        final boolean addQuote = false;

        final Function<ResponseHeaders, Boolean> condition = condBuilder != null ? condBuilder.build() : null;
        if (TimestampComponent.isSupported(type)) {
            return new TimestampComponent(addQuote, variable);
        }
        if (CommonComponent.isSupported(type)) {
            return new CommonComponent(type, addQuote, condition, variable);
        }
        if (HttpHeaderComponent.isSupported(type)) {
            assert variable != null;
            return new HttpHeaderComponent(type, HttpHeaderNames.of(variable), addQuote, condition);
        }
        if (AttributeComponent.isSupported(type)) {
            assert variable != null;
            final Function<Object, String> stringifier;
            final String[] components = variable.split(":");
            if (components.length == 2) {
                stringifier = newStringifier(components[0], components[1]);
            } else {
                stringifier = Object::toString;
            }
            return new AttributeComponent(components[0], stringifier, addQuote, condition);
        }
        if (RequestLogComponent.isSupported(type)) {
            assert variable != null;
            return new RequestLogComponent(variable, addQuote, condition);
        }

        // Should not reach here.
        throw new Error("Unexpected access log type: " + type.name());
    }

    private static String newStringAndReset(StringBuilder textBuilder) {
        final String str = textBuilder.toString();
        textBuilder.setLength(0);
        return str;
    }

    @SuppressWarnings("unchecked")
    private static Function<Object, String> newStringifier(String attrName, String className) {
        final Function<Object, String> stringifier;
        try {
            stringifier = (Function<Object, String>)
                    Class.forName(className, true, AccessLogFormats.class.getClassLoader())
                         .getDeclaredConstructor()
                         .newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to instantiate a stringifier function: " +
                                               attrName, e);
        }
        return stringifier;
    }

    /**
     * A condition based on {@link HttpStatus} of the HTTP response.
     */
    // TODO(ikhoon): Use Predicate<ResponseHeaders>
    private static final class Condition implements Function<ResponseHeaders, Boolean> {

        private final Set<HttpStatus> statusSet;
        private final boolean sign;

        Condition(Set<HttpStatus> statusSet, boolean sign) {
            this.statusSet = statusSet;
            this.sign = sign;
        }

        public Set<HttpStatus> statusSet() {
            return statusSet;
        }

        public boolean isSign() {
            return sign;
        }

        @Override
        public Boolean apply(ResponseHeaders headers) {
            return statusSet.contains(headers.status()) == sign;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("sign", isSign())
                              .add("statusSet", statusSet())
                              .toString();
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private final ImmutableSet.Builder<HttpStatus> statusSet = ImmutableSet.builder();
            private boolean sign = true;
            private boolean isEmpty = true;

            Builder setSign(boolean sign) {
                this.sign = sign;
                return this;
            }

            Builder addHttpStatus(String text) {
                final HttpStatus s = HttpStatus.valueOf(Integer.parseInt(text));
                statusSet.add(s);
                isEmpty = false;
                return this;
            }

            boolean isEmpty() {
                return isEmpty;
            }

            Function<ResponseHeaders, Boolean> build() {
                return new Condition(statusSet.build(), sign);
            }
        }
    }

    /**
     * Parsing states.
     */
    private enum State {
        TEXT,
        PERCENT,
        CONDITION,
        VARIABLE,
        TOKEN
    }

    private AccessLogFormats() {}
}
