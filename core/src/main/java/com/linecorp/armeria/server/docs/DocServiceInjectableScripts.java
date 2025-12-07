/*
 *  Copyright 2025 LY Corporation
 *
 *  LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.server.docs;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provides utilities for {@link DocServiceBuilder#injectedScripts(String...)}.
 */
@UnstableApi
public final class DocServiceInjectableScripts {

    private static final String HEX_COLOR_PATTERN = "^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$";
    private static final int MAX_COLOR_LENGTH = 7;
    private static final String SAFE_DOM_HOOK = "data-js-target";
    private static final String TITLE_BACKGROUND_KEY = "titleBackground";
    private static final String GOTO_BACKGROUND_KEY = "gotoBackground";
    private static final String FAVICON_KEY = "favicon";

    /**
     * Returns a js script to change the title background to the specified color in hex code format.
     *
     * @param color the color string to set
     * @return the js script
     */
    public static String titleBackground(String color) {
        final String targetAttr = "main-app-bar";
        validateHexColor(color, TITLE_BACKGROUND_KEY);

        return buildStyleScript(checkHashtagInHexColorCode(color), targetAttr);
    }

    /**
     * Returns a js script to change the background of the goto component to the specified color in hex code
     * format.
     *
     * @param color the color string to set
     * @return the js script
     */
    public static String gotoBackground(String color) {
        final String targetAttr = "goto-app-bar";
        validateHexColor(color, GOTO_BACKGROUND_KEY);

        return buildStyleScript(checkHashtagInHexColorCode(color), targetAttr);
    }

    /**
     * Returns a js script to change the web favicon.
     *
     * @param uri the uri string to set
     * @return the js script
     */
    public static String favicon(String uri) {
        validateFaviconUri(uri, FAVICON_KEY);

        return buildFaviconScript(escapeJavaScriptUri(uri));
    }

    private DocServiceInjectableScripts() {}

    /**
     * Validates that the given color is a non-null, non-empty, character hex color string.
     *
     * @param color the color string to validate
     * @param key the name used in error messages
     */
    private static void validateHexColor(String color, String key) {
        requireNonNull(color, key);
        checkArgument(!color.trim().isEmpty(), "%s is empty.", key);
        checkArgument(color.length() <= MAX_COLOR_LENGTH,
                      "%s length exceeds %s.", key, MAX_COLOR_LENGTH);
        checkArgument(Pattern.matches(HEX_COLOR_PATTERN, color),
                      "%s not in hex format: %s.", key, color);
    }

    /**
     * Check if the given color starts with a hashtag char.
     *
     * @param color the color string to validate
     * @return hex color string with hashtag included
     */
    private static String checkHashtagInHexColorCode(String color) {

        if (color.startsWith("#")) {
            return color;
        }
        return '#' + color;
    }

    /**
     * Builds a JavaScript snippet that sets the background color of a DOM element.
     *
     * @param color the background color in hex format
     * @param targetAttr the value of the target attribute to match
     * @return a JavaScript string that applies the background color to the element
     */
    private static String buildStyleScript(String color, String targetAttr) {
        return "{\n" +
               "  const element = document.querySelector('[" + SAFE_DOM_HOOK + "=\"" + targetAttr + "\"]');\n" +
               "  if (element) {\n" +
               "    element.style.backgroundColor = '" + color + "';\n" +
               "  }\n}\n";
    }

    /**
     * Validates the favicon uri.
     *
     * @param uri the uri string to validate
     * @param key the name used in error messages
     */
    private static void validateFaviconUri(String uri, String key) {
        requireNonNull(uri, key);
        checkArgument(!uri.trim().isEmpty(), "%s is empty.", key);
    }

    /**
     * Escapes special characters not filtered by other methods.
     *
     * @param uri the input string to escape
     * @return the escaped string
     */
    private static String escapeJavaScriptUri(String uri) {
        final StringBuilder escaped = new StringBuilder();

        for (int i = 0; i < uri.length(); i++) {
            final char c = uri.charAt(i);

            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\'':
                    escaped.append("\\'");
                    break;
                case '&':
                    escaped.append("\\u0026");
                    break;
                case '=':
                    escaped.append("\\u003D");
                    break;
                case '/':
                    escaped.append("\\/");
                    break;
                default:
                    if (c > 126) {
                        escaped.append(String.format("\\u%04X", (int) c));
                    } else {
                        escaped.append(c);
                    }
                    break;
            }
        }

        return escaped.toString();
    }

    /**
     * Builds a JavaScript snippet that sets the new favicon.
     *
     * @param uri the uri string to set
     * @return a JavaScript string that applies the favicon change
     */
    private static String buildFaviconScript(String uri) {
        return "{\n" +
               "  let link = document.querySelector('link[rel~=\"icon\"]');\n" +
               "  if (link) {\n" +
               "    document.head.removeChild(link);\n" +
               "  }\n" +
               "  link = document.createElement('link');\n" +
               "  link.rel = 'icon';\n" +
               "  link.type = 'image/x-icon';\n" +
               "  link.href = '" + uri + "';\n" +
               "  document.head.appendChild(link);\n" +
               "}\n";
    }
}
