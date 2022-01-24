/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.net.URLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

enum DefaultMediaTypeResolver implements MediaTypeResolver {
    INSTANCE;

    /**
     * A map from extension to the {@link MediaType}, which is queried before
     * {@link URLConnection#guessContentTypeFromName(String)}, so that
     * important extensions are always mapped to the right {@link MediaType}.
     */
    private static final Map<String, MediaType> EXTENSION_TO_MEDIA_TYPE;

    static {
        final Map<String, MediaType> map = new HashMap<>();

        // Text files
        add(map, MediaType.CSS_UTF_8, "css");
        add(map, MediaType.HTML_UTF_8, "html", "htm");
        add(map, MediaType.PLAIN_TEXT_UTF_8, "txt");

        // Image files
        add(map, MediaType.GIF, "gif");
        add(map, MediaType.JPEG, "jpeg", "jpg");
        add(map, MediaType.PNG, "png");
        add(map, MediaType.SVG_UTF_8, "svg", "svgz");
        add(map, MediaType.create("image", "x-icon"), "ico");

        // Font files
        add(map, MediaType.create("application", "x-font-ttf"), "ttc", "ttf");
        add(map, MediaType.WOFF, "woff");
        add(map, MediaType.create("application", "font-woff2"), "woff2");
        add(map, MediaType.EOT, "eot");
        add(map, MediaType.create("font", "opentype"), "otf");

        // JavaScript, XML, etc
        add(map, MediaType.JAVASCRIPT_UTF_8, "js");
        add(map, MediaType.JSON_UTF_8, "json");
        add(map, MediaType.PDF, "pdf");
        add(map, MediaType.XHTML_UTF_8, "xhtml", "xhtm");
        add(map, MediaType.APPLICATION_XML_UTF_8, "xml", "xsd");
        add(map, MediaType.create("application", "xml-dtd"), "dtd");

        EXTENSION_TO_MEDIA_TYPE = Collections.unmodifiableMap(map);
    }

    @Nullable
    public MediaType guessFromPath(String path) {
        requireNonNull(path, "path");
        final int dotIdx = path.lastIndexOf('.');
        final int slashIdx = path.lastIndexOf('/');
        if (dotIdx < 0 || slashIdx > dotIdx) {
            // No extension
            return null;
        }

        final String extension = Ascii.toLowerCase(path.substring(dotIdx + 1));
        final MediaType mediaType = EXTENSION_TO_MEDIA_TYPE.get(extension);
        if (mediaType != null) {
            return mediaType;
        }
        final String guessedContentType = URLConnection.guessContentTypeFromName(path);
        return guessedContentType != null ? MediaType.parse(guessedContentType) : null;
    }

    @Nullable
    public MediaType guessFromPath(String path, @Nullable String contentEncoding) {
        if (contentEncoding == null || Ascii.equalsIgnoreCase(contentEncoding, "identity")) {
            return guessFromPath(path);
        }

        requireNonNull(path, "path");
        // If the path is for a precompressed file, it will have an additional extension indicating the
        // encoding, which we don't want to use when determining content type.
        return guessFromPath(path.substring(0, path.lastIndexOf('.')));
    }

    private static void add(Map<String, MediaType> extensionToMediaType,
                            MediaType mediaType, String... extensions) {

        for (String e : extensions) {
            assert Ascii.toLowerCase(e).equals(e);
            extensionToMediaType.put(e, mediaType);
        }
    }
}
