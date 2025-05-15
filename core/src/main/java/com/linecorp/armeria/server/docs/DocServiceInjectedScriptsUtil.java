/*
 *  Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.server.docs;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import com.google.common.collect.ImmutableSet;

/**
 * Util class for DocServiceBuilder#injectedScripts method.
 */
public final class DocServiceInjectedScriptsUtil {

    private static final String HEX_COLOR_PATTERN = "^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$";
    private static final int MAX_COLOR_LENGTH = 7;
    private static final String SAFE_DOM_HOOK = "data-js-target";
    private static final ImmutableSet<String> ALLOWED_FAVICON_EXTENSIONS =
            ImmutableSet.of(".ico", ".png", ".svg");

    /**
     * Returns a js script to change the title background color.
     *
     * @param color the color string to set
     * @return the js script
     */
    public static String withTitleBackground(String color) {
      final String titleBackgroundKey = "titleBackground";
      final String targetAttr = "main-app-bar";
      validateHexColor(color, titleBackgroundKey);

      return buildStyleScript(color, targetAttr);
    }

    /**
     * Returns a js script to change the goto component background color.
     *
     * @param color the color string to set
     * @return the js script
     */
    public static String withGotoBackground(String color) {
        final String gotoBackgroundKey = "gotoBackground";
        final String targetAttr = "goto-app-bar";
        validateHexColor(color, gotoBackgroundKey);

        return buildStyleScript(color, targetAttr);
    }

    /**
     * Returns a js script to change the web favicon.
     *
     * @param url the url string to set
     * @return the js script
     */
    public static String withFavicon(String url) {
        final String faviconKey = "favicon";
        validateFaviconUrl(url, faviconKey);

        return buildFaviconScript(escapeForJavaScriptUrl(url));
    }

    private DocServiceInjectedScriptsUtil() {}

    /**
     * Validates the favicon url.
     *
     * @param url the url string to validate
     * @param key the name used in error messages
     */
    private static void validateFaviconUrl(String url, String key) {
        requireNonNull(url, key);
        checkArgument(!url.trim().isEmpty(), "%s is empty.", key);
        checkArgument(hasValidFaviconExtension(url), "%s extension not allowed.",key);
    }

    /**
     * Validates the favicon extension.
     *
     * @param url the url string
     * @return the result of validation
     */
    private static boolean hasValidFaviconExtension(String url) {
        final String lowerUrl = url.toLowerCase();
        return ALLOWED_FAVICON_EXTENSIONS.stream()
                     .anyMatch(lowerUrl::endsWith);
    }

    /**
     * Escapes special characters in a string to safely embed it in a JavaScript string literal.
     *
     * @param url the input string to escape
     * @return the escaped string
     */
    private static String escapeForJavaScriptUrl(String url) {
        final StringBuilder escaped = new StringBuilder(url.length());

        for (char c : url.toCharArray()) {
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\'':
                    escaped.append("\\'");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case ';':
                case '\n':
                case '\r':
                    break;
                default:
                    escaped.append(c);
            }
        }

        return escaped.toString();
    }

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
     * Builds a JavaScript snippet that sets the new favicon.
     *
     * @param url the url string to set
     * @return a JavaScript string that applies the favicon change
     */
    private static String buildFaviconScript(String url) {
        return "{\n" +
               "  let link = document.querySelector('link[rel~=\"icon\"]');\n" +
               "  if (link) {\n" +
               "    document.head.removeChild(link);\n" +
               "  }\n" +
               "  link = document.createElement('link');\n" +
               "  link.rel = 'icon';\n" +
               "  link.type = 'image/x-icon';\n" +
               "  link.href = '" + url + "';\n" +
               "  document.head.appendChild(link);\n" +
               "}\n";
    }
}
