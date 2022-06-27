package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.server.annotation.Description
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Markup
import com.linecorp.armeria.server.annotation.Param

@CoroutineNameDecorator(name = "default")
class MarkdownDescriptionService {
    @Description(value = """
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
        """, markup = Markup.MARKDOWN)
    @Get("/markdown")
    fun markdown(
        @Description(value = "`Param` description", markup = Markup.MARKDOWN)
        @Param param1: String,
        @Param param2: String,
        @Description("param3 description")
        @Param param3: MarkdownEnumParam
    ): MarkdownDescriptionResult {
        return MarkdownDescriptionResult(
            result1 = param1,
            result2 = param2,
            result3 = param3.name
        )
    }

    @Description(value = """
        ## Structs description
        ### Structs description subtitle
        > Support blockquotes
    """, markup = Markup.MARKDOWN)
    data class MarkdownDescriptionResult(
        @field:Description(value = "result1 description (default)", markup = Markup.MARKDOWN)
        val result1: String,
        @field:Description(value = "`result2` **description** (use markdown)", markup = Markup.MARKDOWN)
        val result2: String,
        @field:Description(value = "`result3` see https://armeria.dev/ (add links)",
                markup = Markup.MARKDOWN)
        val result3: String
    )

    @Description("MarkdownEnumParam")
    enum class MarkdownEnumParam {
        @field:Description(value = "Description for `ENUM_1`", markup = Markup.MARKDOWN)
        ENUM_1,
        ENUM_2,
        ENUM_3
    }
}
