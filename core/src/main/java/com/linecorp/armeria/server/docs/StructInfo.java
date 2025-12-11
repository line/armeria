/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about a struct type.
 */
@UnstableApi
public final class StructInfo implements DescriptiveTypeInfo {

    private final String name;
    @Nullable
    private final String alias;
    private final List<FieldInfo> fields;
    private final DescriptionInfo descriptionInfo;

    private final List<TypeSignature> oneOf;
    @Nullable
    private final DiscriminatorInfo discriminator;

    /**
     * Creates a new instance.
     */
    public StructInfo(String name, Iterable<FieldInfo> fields) {
        this(name, null, fields, DescriptionInfo.empty(), ImmutableList.of(), null);
    }

    /**
     * Creates a new instance.
     */
    public StructInfo(String name, Iterable<FieldInfo> fields, DescriptionInfo descriptionInfo) {
        this(name, null, fields, descriptionInfo, ImmutableList.of(), null);
    }

    /**
     * Creates a new instance.
     */
    public StructInfo(String name, @Nullable String alias, Iterable<FieldInfo> fields,
                      DescriptionInfo descriptionInfo, Iterable<TypeSignature> oneOf,
                      @Nullable DiscriminatorInfo discriminator) {
        this.name = requireNonNull(name, "name");
        this.alias = alias;
        this.fields = ImmutableList.copyOf(requireNonNull(fields, "fields"));
        this.descriptionInfo = requireNonNull(descriptionInfo, "descriptionInfo");
        this.oneOf = ImmutableList.copyOf(requireNonNull(oneOf, "oneOf"));
        this.discriminator = discriminator;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Returns the alias of the {@link #name()}.
     * An alias could be set when a {@link StructInfo} has two different names.
     *
     * <p>For example, if a {@link StructInfo} is extracted from a {@code com.google.protobuf.Message},
     * the {@link StructInfo#name()} is set to the full name defined in the proto file and the
     * {@link StructInfo#alias()} is set to the {@linkplain Class#getName() name} of the generated
     * {@link Class}.
     */
    @Nullable
    @JsonInclude(Include.NON_NULL)
    @JsonProperty
    public String alias() {
        return alias;
    }

    /**
     * Returns a new {@link StructInfo} with the specified {@code alias}.
     * Returns {@code this} if this {@link StructInfo} has the same {@link FieldInfo}s.
     */
    public StructInfo withAlias(String alias) {
        requireNonNull(alias, "alias");
        if (alias.equals(this.alias)) {
            return this;
        }

        return new StructInfo(name, alias, fields, descriptionInfo, oneOf, discriminator);
    }

    /**
     * Returns the metadata about the fields of the type.
     */
    @JsonProperty
    public List<FieldInfo> fields() {
        return fields;
    }

    /**
     * Returns a new {@link StructInfo} with the specified {@link FieldInfo}s.
     * Returns {@code this} if this {@link StructInfo} has the same {@link FieldInfo}s.
     */
    public StructInfo withFields(Iterable<FieldInfo> fields) {
        requireNonNull(fields, "fields");
        if (fields.equals(this.fields)) {
            return this;
        }

        return new StructInfo(name, alias, fields, descriptionInfo, oneOf, discriminator);
    }

    /**
     * Returns the description information of this struct.
     */
    @JsonProperty
    @Override
    public DescriptionInfo descriptionInfo() {
        return descriptionInfo;
    }

    /**
     * Returns a new {@link StructInfo} with the specified {@link DescriptionInfo}.
     * Returns {@code this} if this {@link StructInfo} has the same {@link DescriptionInfo}.
     */
    public StructInfo withDescriptionInfo(DescriptionInfo descriptionInfo) {
        requireNonNull(descriptionInfo, "descriptionInfo");
        if (descriptionInfo.equals(this.descriptionInfo)) {
            return this;
        }

        return new StructInfo(name, alias, fields, descriptionInfo, oneOf, discriminator);
    }

    /**
     * Returns the list of subtypes for polymorphism. This corresponds to the {@code oneOf} keyword
     * in the OpenAPI Specification.
     *
     * @return a list of {@link TypeSignature}s for the possible subtypes.
     */
    @JsonProperty
    @JsonInclude(Include.NON_EMPTY)
    public List<TypeSignature> oneOf() {
        return oneOf;
    }

    /**
     * Returns the discriminator information for polymorphism. This corresponds to the {@code discriminator}
     * object in the OpenAPI Specification.
     *
     * @return the {@link DiscriminatorInfo} object, or {@code null} if not defined.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public DiscriminatorInfo discriminator() {
        return discriminator;
    }

    @Override
    public Set<DescriptiveTypeSignature> findDescriptiveTypes() {
        final Set<DescriptiveTypeSignature> collectedDescriptiveTypes = new HashSet<>();
        fields().forEach(f -> ServiceInfo.findDescriptiveTypes(collectedDescriptiveTypes, f.typeSignature()));
        oneOf().forEach(t -> ServiceInfo.findDescriptiveTypes(collectedDescriptiveTypes, t));
        return ImmutableSortedSet.copyOf(comparing(TypeSignature::name), collectedDescriptiveTypes);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof StructInfo)) {
            return false;
        }

        final StructInfo that = (StructInfo) o;
        return name.equals(that.name) &&
               Objects.equals(alias, that.alias) &&
               fields.equals(that.fields) &&
               descriptionInfo.equals(that.descriptionInfo) &&
               oneOf.equals(that.oneOf) &&
               Objects.equals(discriminator, that.discriminator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, alias, fields, descriptionInfo, oneOf, discriminator);
    }

    @Override
    public String toString() {
        final MoreObjects.ToStringHelper stringHelper =
                MoreObjects.toStringHelper(this)
                           .add("name", name)
                           .add("alias", alias)
                           .add("fields", fields)
                           .add("descriptionInfo", descriptionInfo);

        if (!oneOf.isEmpty()) {
            stringHelper.add("oneOf", oneOf);
        }

        if (discriminator != null) {
            stringHelper.add("discriminator", discriminator);
        }

        return stringHelper.toString();
    }
}
