/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A pair of name and labels that is used for identifying a {@link Metric} in {@link Metrics}.
 *
 * <p>A metric name is represented as a list of strings (name parts). Note that it is not a single string
 * delimited by a special character such as {@code '.'} or {@code '_'}.
 *
 * <p>Labels annotate a metric name to differentiate the characteristics of what's being measured.
 * For example, a metric name {@code ["request", "active"]}, which signifies the number of active requests,
 * could be annotated with a label {@code "protocol"} so it's counted separately for each protocol type,
 * such as {@code "protocol=H1C"} and {@code "protocol=H2"}.
 *
 * <p>Note that two {@link MetricKey}s with same name and different labels are considered different.
 */
public final class MetricKey {

    private final List<String> nameParts;
    private final Map<MetricLabel, String> labels;
    private int hashCode;

    /**
     * Creates a new instance with the specified name parts and with no labels.
     *
     * @throws IllegalArgumentException if the name parts 1) is empty or 2) contains an empty string
     */
    public MetricKey(String... nameParts) {
        this(ImmutableList.copyOf(requireNonNull(nameParts, "nameParts")));
    }

    /**
     * Creates a new instance with the specified name parts and with no labels.
     *
     * @throws IllegalArgumentException if the name parts 1) is empty or 2) contains an empty string
     */
    public MetricKey(Iterable<String> nameParts) {
        this(nameParts, ImmutableMap.of());
    }

    /**
     * Creates a new instance with the specified name parts and labels.
     *
     * @param nameParts a {@link List} of name parts
     * @param labels a {@link Map} whose key is a {@link MetricLabel} or a {@link CharSequence}
     *
     * @throws IllegalArgumentException if 1) both name parts and labels are empty
     *                                  2) the name parts contains an empty string or
     *                                  3) the labels contains an empty name or value
     */
    public MetricKey(Iterable<String> nameParts, Map<?, String> labels) {
        this.nameParts = validateNameParts(nameParts);

        if (labels.isEmpty()) {
            this.labels = ImmutableMap.of();
        } else {
            final ImmutableMap.Builder<MetricLabel, String> builder = ImmutableMap.builder();
            final Set<String> labelNames = new HashSet<>(labels.size());
            labels.forEach((k, v) -> {
                final String name;
                if (k instanceof MetricLabel) {
                    name = ((MetricLabel) k).name();
                    builder.put((MetricLabel) k, v);
                } else if (k instanceof CharSequence) {
                    name = k.toString();
                    builder.put(new MetricLabelImpl(name), v);
                } else {
                    throw new IllegalArgumentException(
                            "labels contains a key of unsupported type: " + k.getClass().getName() +
                            " (expected: " + MetricLabel.class.getSimpleName() +
                            " or " + CharSequence.class.getSimpleName() + ')');
                }

                checkArgument(!name.isEmpty(),
                              "labels contains an empty label: %s", labels);
                checkArgument(labelNames.add(name),
                              "labels contains duplicate label names: %s", labels);
            });

            this.labels = builder.build();
        }

        checkArgument(!this.nameParts.isEmpty() || !this.labels.isEmpty(),
                      "both nameParts and labels are empty.");
    }

    static List<String> validateNameParts(Iterable<String> nameParts) {
        final List<String> copy = ImmutableList.copyOf(requireNonNull(nameParts, "nameParts"));
        checkArgument(copy.stream().noneMatch(String::isEmpty),
                      "nameParts contains an empty string: %s", copy);
        return copy;
    }

    /**
     * Returns the name parts of this key.
     */
    public List<String> nameParts() {
        return nameParts;
    }

    /**
     * Returns the labels of this key.
     */
    public Map<MetricLabel, String> labels() {
        return labels;
    }

    /**
     * Returns whether the specified key's name starts with this key's name and the specified key has all
     * labels in this key. For example, with the following metric keys:
     * <ul>
     *   <li>{@code "a.b{foo=1}"}</li>
     *   <li>{@code "a.c{foo=1,bar=2}"}</li>
     * </ul>
     * you will observe that:
     * <ul>
     *   <li>{@code "a"} will include both keys.</li>
     *   <li>{@code "a.b"} will include the first key.</li>
     *   <li>{@code "a{foo=1}"} will include both keys.</li>
     *   <li>{@code "a{bar=2}"} will include the second key.</li>
     * </ul>
     */
    public boolean includes(MetricKey key) {
        requireNonNull(key, "key");

        final List<String> myNameParts = nameParts;
        final List<String> yourNameParts = key.nameParts;

        // Filter out the elements whose names do not start with prefix.
        if (myNameParts.size() > yourNameParts.size() ||
            !myNameParts.equals(yourNameParts.subList(0, myNameParts.size()))) {
            return false;
        }

        return key.labels.entrySet().containsAll(labels.entrySet());
    }

    /**
     * Returns a newly created {@link MetricKey} whose name is prepended by the specified name part.
     */
    public MetricKey prepend(String namePart) {
        validateNamePart(namePart);
        return new MetricKey(ImmutableList.<String>builder().add(namePart).addAll(nameParts).build(),
                             labels);
    }

    /**
     * Returns a newly created {@link MetricKey} whose name is appended by the specified name part.
     */
    public MetricKey append(String namePart) {
        validateNamePart(namePart);
        return new MetricKey(ImmutableList.<String>builder().addAll(nameParts).add(namePart).build(),
                             labels);
    }

    /**
     * Returns a newly created {@link MetricKey} which has the specified label added.
     */
    public MetricKey withLabel(String label, String value) {
        validateLabel(label, value);

        return withLabel(new MetricLabelImpl(label), value);
    }

    /**
     * Returns a newly created {@link MetricKey} which has the specified label added.
     */
    public MetricKey withLabel(MetricLabel label, String value) {
        validateLabel(label, value);
        final ImmutableMap<MetricLabel, String> newLabels = ImmutableMap.<MetricLabel, String>builder()
                .putAll(labels).put(label, value).build();
        return new MetricKey(nameParts, newLabels);
    }

    /**
     * Returns a newly created {@link MetricKey} which has the specified labels added.
     */
    public MetricKey withLabels(Map<?, String> labels) {
        requireNonNull(labels, "labels");
        if (labels.isEmpty()) {
            return this;
        }

        final ImmutableMap.Builder<MetricLabel, String> builder = ImmutableMap.builder();
        if (!this.labels.isEmpty()) {
            builder.putAll(this.labels);
        }

        labels.forEach((k, v) -> {
            if (k instanceof MetricLabel) {
                builder.put((MetricLabel) k, v);
            } else if (k instanceof CharSequence) {
                builder.put(new MetricLabelImpl(k.toString()), v);
            } else {
                throw new IllegalArgumentException(
                        "labels contains a key of unsupported type: " + k.getClass().getName() +
                        " (expected: " + MetricLabel.class.getSimpleName() +
                        " or " + CharSequence.class.getSimpleName() + ')');
            }
        });

        return new MetricKey(nameParts, builder.build());
    }

    static void validateNamePart(String namePart) {
        requireNonNull(namePart, "namePart");
        checkArgument(!namePart.isEmpty(), "empty namePart");
    }

    static void validateLabel(String label, String value) {
        requireNonNull(label, "label");
        checkArgument(!label.isEmpty(), "empty label");
        requireNonNull(value, "value");
    }

    static void validateLabel(MetricLabel label, String value) {
        requireNonNull(label, "label");
        checkArgument(!label.name().isEmpty(), "empty label name");
        requireNonNull(value, "value");
    }

    @Override
    public int hashCode() {
        if (hashCode != 0) {
            return hashCode;
        }

        return hashCode = nameParts.hashCode() * 31 + labels.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof MetricKey)) {
            return false;
        }

        final MetricKey that = (MetricKey) o;
        return hashCode() == that.hashCode() &&
               nameParts.equals(that.nameParts) &&
               labels.equals(that.labels);
    }

    @Override
    public String toString() {
        final String name = String.join(".", nameParts);
        if (labels.isEmpty()) {
            return name;
        }

        // We use Joiner here because labels.toString() inserts space after comma (,).
        return name + '{' + Joiner.on(',').join(labels.entrySet()) + '}';
    }

    private static class MetricLabelImpl implements MetricLabel {

        private final String label;

        MetricLabelImpl(String label) {
            this.label = label;
        }

        @Override
        public String name() {
            return label;
        }

        @Override
        public int hashCode() {
            return label.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof MetricLabelImpl)) {
                return false;
            }

            return label.equals(((MetricLabelImpl) obj).label);
        }

        @Override
        public String toString() {
            return name();
        }
    }
}
