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
package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.server.annotation.Description
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.docs.Markup

@CoroutineNameDecorator(name = "default")
class MarkdownDescriptionService {
    @Description(
        value = """
        ## Support markdown
        ### Support header
        - Support lists
        
        ### Support code-highlight
        ```java
        Server
          .builder()
          .service(
            "/hello",
            (ctx, req) -> HttpResponse.of("Hello!"))
          .service(GrpcService
            .builder()
            .addService(myGrpcServiceImpl)
            .build())
          .service(
            "/api/thrift",
            ThriftService.of(myThriftServiceImpl))
          .service(
            "prefix:/files",
            FileService.of(new File("/var/www")))
          .service(
            "/monitor/l7check",
            HealthCheckService.of())
          .build()
          .start();
        ```
        """,
        markup = Markup.MARKDOWN,
    )
    @Get("/markdown")
    fun markdown(
        @Description(value = "`Param` description", markup = Markup.MARKDOWN)
        @Param
        param1: String,
        @Param param2: String,
        @Description("param3 description")
        @Param
        param3: MarkdownEnumParam,
    ): MarkdownDescriptionResult {
        return MarkdownDescriptionResult(
            result1 = param1,
            result2 = param2,
            result3 = param3.name,
        )
    }

    @Description(
        value = """
        ## Structs description
        ### Structs description subtitle
        > Support blockquotes
        """,
        markup = Markup.MARKDOWN,
    )
    data class MarkdownDescriptionResult(
        @Description(value = "result1 description (default)", markup = Markup.MARKDOWN)
        val result1: String,
        @Description(value = "`result2` **description** (use markdown)", markup = Markup.MARKDOWN)
        val result2: String,
        @Description(value = "`result3` see https://armeria.dev/ (add links)", markup = Markup.MARKDOWN)
        val result3: String,
    )

    @Description("MarkdownEnumParam")
    enum class MarkdownEnumParam {
        @Description(value = "Description for `ENUM_1`", markup = Markup.MARKDOWN)
        ENUM_1,
        ENUM_2,
        ENUM_3,
    }
}
