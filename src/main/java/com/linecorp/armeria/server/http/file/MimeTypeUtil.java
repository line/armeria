/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.file;

import static java.util.Objects.requireNonNull;

import java.net.URLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class MimeTypeUtil {

    /**
     * A map from extension to MIME types, which is queried before
     * {@link URLConnection#guessContentTypeFromName(String)}, so that
     * important extensions are always mapped to the right MIME types.
     */
    private static final Map<String, String> EXTENSION_TO_MIME_TYPE;

    static {
        final Map<String, String> map = new HashMap<>();

        // Text files
        add(map, "text/css", "css");
        add(map, "text/html", "html", "htm");
        add(map, "text/plain", "txt");

        // Image files
        add(map, "image/gif", "gif");
        add(map, "image/jpeg", "jpeg", "jpg");
        add(map, "image/png", "png");
        add(map, "image/svg+xml", "svg", "svgz");
        add(map, "image/x-icon", "ico");

        // Font files
        add(map, "application/x-font-ttf", "ttc", "ttf");
        add(map, "application/font-woff", "woff");
        add(map, "application/font-woff2", "woff2");
        add(map, "application/vnd.ms-fontobject", "eot");
        add(map, "font/opentype", "otf");

        // JavaScript, XML, etc
        add(map, "application/javascript", "js");
        add(map, "application/json", "json");
        add(map, "application/pdf", "pdf");
        add(map, "application/xhtml+xml", "xhtml", "xhtm");
        add(map, "application/xml", "xml", "xsd");
        add(map, "application/xml-dtd", "dtd");

        EXTENSION_TO_MIME_TYPE = Collections.unmodifiableMap(map);
    }

    private static void add(Map<String, String> extensionToMimeType, String mimeType, String... extensions) {
        for (String e : extensions) {
            assert e.toLowerCase(Locale.US).equals(e);
            extensionToMimeType.put(e, mimeType);
        }
    }

    static String guessFromPath(String path) {
        requireNonNull(path, "path");
        final int dotIdx = path.lastIndexOf('.');
        final int slashIdx = path.lastIndexOf('/');
        if (dotIdx < 0 || slashIdx > dotIdx) {
            // No extension
            return null;
        }

        final String extension = path.substring(dotIdx + 1).toLowerCase(Locale.US);
        final String mimeType = EXTENSION_TO_MIME_TYPE.get(extension);
        return mimeType != null ? mimeType : URLConnection.guessContentTypeFromName(path);
    }

    private MimeTypeUtil() {}
}
