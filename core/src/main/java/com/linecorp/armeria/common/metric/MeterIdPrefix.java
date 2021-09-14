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

package com.linecorp.armeria.common.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * A common prefix of {@link Meter.Id} which consists of {@link Meter} name and {@link Tag}s.
 */
public final class MeterIdPrefix {

    private final String name;
    private final ImmutableList<Tag> tags;
    private int hashCode;

    /**
     * Creates a new instance with no {@link Tag}s.
     *
     * @param name the {@link Meter} name
     */
    public MeterIdPrefix(String name) {
        this(name, ImmutableList.of());
    }

    /**
     * Creates a new instance.
     *
     * @param name the {@link Meter} name
     * @param tags the keys and values of the {@link Tag}s
     */
    public MeterIdPrefix(String name, String... tags) {
        this(name, zipAndSort(requireNonNull(tags, "tags")));
    }

    /**
     * Creates a new instance.
     *
     * @param name the {@link Meter} name
     * @param tags the {@link Tag}s of the {@link Meter}
     */
    public MeterIdPrefix(String name, Iterable<Tag> tags) {
        this(name, copyAndSort(requireNonNull(tags, "tags")));
    }

    private MeterIdPrefix(String name, ImmutableList<Tag> tags) {
        this.name = requireNonNull(name, "name");
        this.tags = tags;
    }

    private static ImmutableList<Tag> zipAndSort(String... tags) {
        if (tags.length == 0) {
            return ImmutableList.of();
        }

        final List<Tag> result = new ArrayList<>(tags.length / 2);
        zip(result, tags);
        return sort(result);
    }

    private static void zip(List<Tag> list, String... tags) {
        checkArgument(tags.length % 2 == 0, "tags.length: %s (expected: even)", tags.length);
        for (int i = 0; i < tags.length;) {
            list.add(Tag.of(tags[i++], tags[i++]));
        }
    }

    private static ImmutableList<Tag> sort(List<Tag> tags) {
        if (tags.isEmpty()) {
            return ImmutableList.of();
        }

        Collections.sort(tags);
        return ImmutableList.copyOf(tags);
    }

    private static ImmutableList<Tag> copyAndSort(Iterable<Tag> tags) {
        return ImmutableList.sortedCopyOf(tags);
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
        return sortedImmutableTags(tags);
    }

    /**
     * Returns the {@link Tag}s concatenated by the specified {@code tags}.
     */
    public List<Tag> tags(Iterable<Tag> tags) {
        return sortedImmutableTags(tags);
    }

    private ImmutableList<Tag> sortedImmutableTags(String[] tags) {
        requireNonNull(tags, "tags");
        if (tags.length == 0) {
            return this.tags;
        }

        final List<Tag> list = new ArrayList<>(this.tags);
        zip(list, tags);
        return sort(list);
    }

    private ImmutableList<Tag> sortedImmutableTags(Iterable<Tag> tags) {
        requireNonNull(tags, "tags");
        if (tags instanceof Collection && ((Collection<?>) tags).isEmpty()) {
            return this.tags;
        }

        final List<Tag> list = new ArrayList<>(this.tags);
        tags.forEach(list::add);
        return sort(list);
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code suffix}.
     */
    public MeterIdPrefix append(String suffix) {
        return new MeterIdPrefix(name(suffix), tags);
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code suffix} and
     * {@code tags}.
     */
    public MeterIdPrefix appendWithTags(String suffix, String... tags) {
        return new MeterIdPrefix(name(suffix), sortedImmutableTags(tags));
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code suffix} and
     * {@code tags}.
     */
    public MeterIdPrefix appendWithTags(String suffix, Iterable<Tag> tags) {
        return new MeterIdPrefix(name(suffix), sortedImmutableTags(tags));
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code tags}.
     */
    public MeterIdPrefix withTags(String... tags) {
        return new MeterIdPrefix(name, sortedImmutableTags(tags));
    }

    /**
     * Returns a newly-created instance whose name is concatenated by the specified {@code tags}.
     */
    public MeterIdPrefix withTags(Iterable<Tag> tags) {
        return new MeterIdPrefix(name, sortedImmutableTags(tags));
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = name.hashCode() * 31 + tags.hashCode();
        }
        return hashCode;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof MeterIdPrefix)) {
            return false;
        }

        final MeterIdPrefix that = (MeterIdPrefix) obj;
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
