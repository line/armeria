/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation

import com.fasterxml.jackson.annotation.JsonProperty
import com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.STRING
import com.linecorp.armeria.server.annotation.Description
import com.linecorp.armeria.server.docs.DescriptionInfo
import com.linecorp.armeria.server.docs.EnumInfo
import com.linecorp.armeria.server.docs.EnumValueInfo
import com.linecorp.armeria.server.docs.FieldInfo
import com.linecorp.armeria.server.docs.FieldRequirement
import com.linecorp.armeria.server.docs.StructInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DataClassDefaultNameTypeInfoProviderTest {
    @CsvSource(value = ["true", "false"])
    @ParameterizedTest
    fun dataClass(request: Boolean) {
        val provider = DefaultDescriptiveTypeInfoProvider(request)
        val struct: StructInfo = (provider.newDescriptiveTypeInfo(DescriptionResult::class.java) as StructInfo)

        assertThat(struct.name()).isEqualTo(DescriptionResult::class.java.name)
        assertThat(struct.descriptionInfo()).isEqualTo(DescriptionInfo.of("Class description"))

        assertThat(struct.fields()).containsExactlyInAnyOrder(
            FieldInfo.builder("required", STRING)
                .requirement(FieldRequirement.REQUIRED)
                .descriptionInfo(DescriptionInfo.of("required description"))
                .build(),
            FieldInfo.builder("optional", STRING)
                .requirement(FieldRequirement.OPTIONAL)
                .descriptionInfo(DescriptionInfo.of("optional description"))
                .build(),
            FieldInfo.builder("defaultValue", STRING)
                .requirement(FieldRequirement.REQUIRED)
                .descriptionInfo(DescriptionInfo.of("default value description"))
                .build(),
            FieldInfo.builder("defaultValue2", STRING)
                .requirement(FieldRequirement.REQUIRED)
                .descriptionInfo(DescriptionInfo.of("default value 2 description"))
                .build(),
            FieldInfo.builder("renamedNonnull", STRING)
                .requirement(FieldRequirement.REQUIRED)
                .descriptionInfo(DescriptionInfo.of("renamed nonnull description"))
                .build(),
            FieldInfo.builder("renamedNullable", STRING)
                .requirement(FieldRequirement.OPTIONAL)
                .descriptionInfo(DescriptionInfo.of("renamed nullable description"))
                .build(),
        )
    }

    @CsvSource(value = ["true", "false"])
    @ParameterizedTest
    fun enumClass(request: Boolean) {
        val requestProvider = DefaultDescriptiveTypeInfoProvider(request)
        val enumInfo: EnumInfo =
            (requestProvider.newDescriptiveTypeInfo(EnumParam::class.java) as EnumInfo)
        assertThat(enumInfo.name()).isEqualTo(EnumParam::class.java.name)
        assertThat(enumInfo.descriptionInfo()).isEqualTo(DescriptionInfo.of("Enum description"))
        assertThat(enumInfo.values()).containsExactlyInAnyOrder(
            EnumValueInfo("ENUM_1", null, DescriptionInfo.of("ENUM_1 description")),
            EnumValueInfo("ENUM_2", null, DescriptionInfo.of("ENUM_2 description")),
        )
    }

    @Description(value = "Class description")
    data class DescriptionResult(
        @Description(value = "required description")
        val required: String,
        @Description(value = "optional description")
        val optional: String?,
        @Description(value = "default value description")
        val defaultValue: String = "Hello",
        @Description(value = "default value 2 description")
        val defaultValue2: String = "Hello2",
        @JsonProperty("renamedNonnull")
        @Description("renamed nonnull description")
        val nonnullName: String,
        @JsonProperty("renamedNullable")
        @Description("renamed nullable description")
        val nullableName: String?,
    )

    @Description("Enum description")
    enum class EnumParam {
        @Description(value = "ENUM_1 description")
        ENUM_1,

        @Description(value = "ENUM_2 description")
        ENUM_2,
    }
}
