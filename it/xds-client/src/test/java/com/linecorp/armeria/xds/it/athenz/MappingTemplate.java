/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.it.athenz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.MappingString;

/**
 * Pre-parsed template from a {@link MappingString}.
 *
 * <p>Supported placeholders: {@code ${host}}, {@code ${method}}, {@code ${path}},
 * {@code ${match.<name>.<index>}}.
 */
final class MappingTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final List<Segment> segments;

    private MappingTemplate(List<Segment> segments) {
        this.segments = segments;
    }

    static MappingTemplate of(MappingString mappingString) {
        switch (mappingString.getStringSpecifierCase()) {
            case LITERAL:
                return literal(mappingString.getLiteral());
            case TEMPLATE:
                return parse(mappingString.getTemplate().getTemplate());
            default:
                throw new IllegalArgumentException(
                        "Unsupported MappingString case: " + mappingString.getStringSpecifierCase());
        }
    }

    private static MappingTemplate literal(String value) {
        final List<Segment> segments = new ArrayList<>(1);
        segments.add(new LiteralSegment(value));
        return new MappingTemplate(segments);
    }

    private static MappingTemplate parse(String template) {
        final List<Segment> segments = new ArrayList<>();
        final Matcher m = PLACEHOLDER.matcher(template);
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() > lastEnd) {
                segments.add(new LiteralSegment(template.substring(lastEnd, m.start())));
            }
            segments.add(parsePlaceholder(m.group(1)));
            lastEnd = m.end();
        }
        if (lastEnd < template.length()) {
            segments.add(new LiteralSegment(template.substring(lastEnd)));
        }
        return new MappingTemplate(segments);
    }

    private static Segment parsePlaceholder(String key) {
        switch (key) {
            case "host":
                return HostSegment.INSTANCE;
            case "method":
                return MethodSegment.INSTANCE;
            case "path":
                return PathSegment.INSTANCE;
            default:
                if (key.startsWith("match.")) {
                    final String rest = key.substring("match.".length());
                    final int dot = rest.lastIndexOf('.');
                    if (dot > 0) {
                        final String name = rest.substring(0, dot);
                        try {
                            final int index = Integer.parseInt(rest.substring(dot + 1));
                            return new CaptureSegment(name, index);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                throw new IllegalArgumentException("Unknown placeholder: ${" + key + '}');
        }
    }

    @Nullable
    String resolve(HttpRequest req, Map<String, List<String>> captures) {
        final StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            final String value = segment.resolve(req, captures);
            if (value == null) {
                return null;
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private interface Segment {
        @Nullable
        String resolve(HttpRequest req, Map<String, List<String>> captures);
    }

    private static final class LiteralSegment implements Segment {
        private final String value;

        LiteralSegment(String value) {
            this.value = value;
        }

        @Override
        public String resolve(HttpRequest req, Map<String, List<String>> captures) {
            return value;
        }
    }

    private static final class HostSegment implements Segment {
        static final HostSegment INSTANCE = new HostSegment();

        @Override
        public String resolve(HttpRequest req, Map<String, List<String>> captures) {
            return req.authority();
        }
    }

    private static final class MethodSegment implements Segment {
        static final MethodSegment INSTANCE = new MethodSegment();

        @Override
        public String resolve(HttpRequest req, Map<String, List<String>> captures) {
            return req.method().name();
        }
    }

    private static final class PathSegment implements Segment {
        static final PathSegment INSTANCE = new PathSegment();

        @Override
        public String resolve(HttpRequest req, Map<String, List<String>> captures) {
            final String path = req.path();
            final int qi = path.indexOf('?');
            return qi >= 0 ? path.substring(0, qi) : path;
        }
    }

    private static final class CaptureSegment implements Segment {
        private final String name;
        private final int index;

        CaptureSegment(String name, int index) {
            this.name = name;
            this.index = index;
        }

        @Override
        @Nullable
        public String resolve(HttpRequest req, Map<String, List<String>> captures) {
            final List<String> groups = captures.get(name);
            if (groups == null || index < 0 || index >= groups.size()) {
                return null;
            }
            return groups.get(index);
        }
    }
}
