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

import java.net.URI;
import java.net.URISyntaxException;
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
    private static final ImmutableSet<String> ALLOWED_SCHEMES = ImmutableSet.of("http", "https");

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
     * @param uri the uri string to set
     * @return the js script
     */
    public static String withFavicon(String uri) {
        final String faviconKey = "favicon";
        validateFaviconUri(uri, faviconKey);

        return buildFaviconScript(escapeJavaScriptUri(uri));
    }

    private DocServiceInjectedScriptsUtil() {}

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
     * Validates the favicon uri.
     *
     * @param uri the uri string to validate
     * @param key the name used in error messages
     */
    private static void validateFaviconUri(String uri, String key) {
        requireNonNull(uri, key);
        checkArgument(!uri.trim().isEmpty(), "%s is empty.", key);
        checkArgument(isValidUri(uri), "%s uri invalid.", key);
        checkArgument(hasValidFaviconExtension(uri), "%s extension not allowed.",key);
    }

    /**
     * Check if the input is a valid URI.
     * @param input the uri string to validate
     * @return true if is valid
     */
    public static boolean isValidUri(String input) {
        try {
            final URI uri = new URI(input);
            final String scheme = uri.getScheme();
            if (scheme == null) {
              return true;
            }
            return ALLOWED_SCHEMES.contains(scheme.toLowerCase());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Validates the favicon extension.
     *
     * @param uri the uri string
     * @return the result of validation
     */
    private static boolean hasValidFaviconExtension(String uri) {
        final String lowerUrl = uri.toLowerCase();
        return ALLOWED_FAVICON_EXTENSIONS.stream()
                     .anyMatch(lowerUrl::endsWith);
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
