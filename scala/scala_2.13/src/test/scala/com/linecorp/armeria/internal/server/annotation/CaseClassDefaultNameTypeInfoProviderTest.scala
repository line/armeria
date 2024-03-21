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

import com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.STRING
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.scala.implicits._
import com.linecorp.armeria.server.annotation.Description
import com.linecorp.armeria.server.docs.{DescriptionInfo, FieldInfo, FieldRequirement, StructInfo}
import munit.FunSuite

@GenerateNativeImageTrace
class CaseClassDefaultNameTypeInfoProviderTest extends FunSuite {

  test(s"should support @Description for case class") {
    List(true, false).foreach { request =>
      val provider = new DefaultDescriptiveTypeInfoProvider(request)
      val struct: StructInfo = provider.newDescriptiveTypeInfo(classOf[TestDescriptionResult]).asInstanceOf[StructInfo]

      assertEquals(struct.name(), classOf[TestDescriptionResult].getName())
      assertEquals(struct.descriptionInfo(), DescriptionInfo.of("Class description"))

      assertEquals(
        struct.fields().asScala.toList,
        List(
          FieldInfo
            .builder("required", STRING)
            .requirement(FieldRequirement.REQUIRED)
            .descriptionInfo(DescriptionInfo.of("required description"))
            .build(),
          FieldInfo
            .builder("optional", STRING)
            .requirement(FieldRequirement.OPTIONAL)
            .descriptionInfo(DescriptionInfo.of("optional description"))
            .build(),
          FieldInfo
            .builder("defaultValue", STRING)
            .requirement(FieldRequirement.REQUIRED)
            .descriptionInfo(DescriptionInfo.of("default value description"))
            .build()
        )
      )
    }
  }
}

@Description("Class description")
case class TestDescriptionResult(
    @Description(value = "required description")
    required: String,
    @Description(value = "optional description")
    optional: Option[String],
    @Description(value = "default value description")
    defaultValue: String = "Hello"
)
