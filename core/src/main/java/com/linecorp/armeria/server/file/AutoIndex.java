/*
 * Copyright 2019 LINE Corporation
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

import java.util.List;

import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;
import com.google.common.net.UrlEscapers;

import com.linecorp.armeria.common.HttpData;

final class AutoIndex {
    private static final String PART1 =
            "<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"><title>Directory listing: ";
    private static final String PART2 =
            "</title><style>" +
            "body { font-family: sans-serif; }" +
            ".container { display: flex; }" +
            ".directory-listing { margin: 0 auto; min-width: 60%; }" +
            ".directory-listing ul { padding-left: 1.75em; }" +
            "</style></head><body>\n" +
            "<div class=\"container\"><div class=\"directory-listing\"><h1>Directory listing: ";
    private static final String PART3 = "</h1>\n<p>";
    private static final String PART4 = " file(s) total\n<ul>\n";
    private static final String PART5 = "</ul></p></div></div></body>\n</html>\n";

    static HttpData listingToHtml(String dirPath, String mappedDirPath, List<String> listing) {
        final Escaper htmlEscaper = HtmlEscapers.htmlEscaper();
        final Escaper urlEscaper = UrlEscapers.urlFragmentEscaper();
        final String escapedDirPath = htmlEscaper.escape(dirPath);
        final StringBuilder buf = new StringBuilder(listing.size() * 64);
        buf.append(PART1);
        buf.append(escapedDirPath);
        buf.append(PART2);
        buf.append(escapedDirPath);
        buf.append(PART3);
        buf.append(listing.size());
        buf.append(PART4);
        if (!"/".equals(mappedDirPath)) {
            buf.append("<li class=\"directory parent\"><a href=\"../\">../</a></li>\n");
        }
        for (String name : listing) {
            buf.append("<li class=\"");
            if (name.charAt(name.length() - 1) == '/') {
                buf.append("directory");
            } else {
                buf.append("file");
            }
            buf.append("\"><a href=\"");
            buf.append(urlEscaper.escape(name));
            buf.append("\">");
            buf.append(name);
            buf.append("</a></li>\n");
        }
        buf.append(PART5);
        return HttpData.ofUtf8(buf.toString());
    }

    private AutoIndex() {}
}
