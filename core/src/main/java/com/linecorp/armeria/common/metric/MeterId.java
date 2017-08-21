/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * An immutable holder of {@link Meter} name and {@link Tag}s.
 */
public final class MeterId {

    private final String name;
    private final List<Tag> tags;
    private int hashCode;

    /**
     * Creates a new instance with no {@link Tag}s.
     *
     * @param name the {@link Meter} name
     */
    public MeterId(String name) {
        this.name = requireNonNull(name, "name");
        tags = ImmutableList.of();
    }

    /**
     * Creates a new instance.
     *
     * @param name the {@link Meter} name
     * @param tags the keys and values of the {@link Tag}s
     */
    public MeterId(String name, String... tags) {
        this.name = requireNonNull(name, "name");
        this.tags = zip(requireNonNull(tags, "tags"));
    }

    /**
     * Creates a new instance.
     *
     * @param name the {@link Meter} name
     * @param tags the {@link Tag}s of the {@link Meter}
     */
    public MeterId(String name, Iterable<Tag> tags) {
        this.name = requireNonNull(name, "name");
        this.tags = ImmutableList.copyOf(requireNonNull(tags, "tags"));
    }

    private static List<Tag> zip(String... tags) {
        final ImmutableList.Builder<Tag> builder = ImmutableList.builder();
        zip(builder, tags);
        return builder.build();
    }

    private static void zip(Builder<Tag> builder, String... tags) {
        checkArgument(tags.length % 2 == 0, "tags.length: %s (expected: even)", tags.length);
        for (int i = 0; i < tags.length;) {
            builder.add(Tag.of(tags[i++], tags[i++]));
        }
    }

    /**
     * Returns the name.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the name concatenated by the specified {@code suffix}.
     */
    public String name(String suffix) {
        requireNonNull(suffix, "suffix");
        return name + '.' + suffix;
    }

    /**
     * Returns the {@link Tag}s.
     */
    public List<Tag> tags() {
        return tags;
    }

    /**
     * Returns the {@link Tag}s concatenated by the specified {@code tags}.
     */
    public List<Tag> tags(String... tags) {
        requireNonNull(tags, "tags");
        final ImmutableList.Builder<Tag> builder = ImmutableList.builder();
        builder.addAll(this.tags);
        zip(builder, tags);
        return builder.build();
    }

    /**
     * Returns the {@link Tag}s concatenated by the specified {@code tags}.
     */
    public List<Tag> tags(Iterable<Tag> tags) {
        requireNonNull(tags, "tags");
        final ImmutableList.Builder<Tag> builder = ImmutableList.builder();
        builder.addAll(this.tags);
        builder.addAll(tags);
        return builder.build();
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code suffix}.
     */
    public MeterId append(String suffix) {
        return new MeterId(name(suffix), tags);
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code suffix} and
     * {@code tags}.
     */
    public MeterId appendWithTags(String suffix, String... tags) {
        return new MeterId(name(suffix), tags(tags));
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code suffix} and
     * {@code tags}.
     */
    public MeterId appendWithTags(String suffix, Iterable<Tag> tags) {
        return new MeterId(name(suffix), tags(tags));
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code tags}.
     */
    public MeterId withTags(String... tags) {
        requireNonNull(tags, "tags");
        final ImmutableList.Builder<Tag> builder = ImmutableList.builder();
        builder.addAll(this.tags);
        zip(builder, tags);
        return new MeterId(name, tags(tags));
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code tags}.
     */
    public MeterId withTags(Iterable<Tag> tags) {
        return new MeterId(name, tags(tags));
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = name.hashCode() * 31 + tags.hashCode();
        }
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof MeterId)) {
            return false;
        }

        final MeterId that = (MeterId) obj;
        return name.equals(that.name) && tags.equals(that.tags);
    }

    @Override
    public String toString() {
        if (tags.isEmpty()) {
            return name;
        }

        final StringBuilder buf = new StringBuilder();
        buf.append(name).append('{');
        tags.forEach(tag -> buf.append(tag.getKey()).append('=')
                               .append(tag.getValue()).append(','));
        buf.setCharAt(buf.length() - 1, '}');
        return buf.toString();
    }
}
