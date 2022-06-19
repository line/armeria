package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.server.annotation.Description
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.ResponseObject

@CoroutineNameDecorator(name = "default")
class MarkdownDescriptionService {
    @Description("""
        ## Support markdown
        ### Support header
        - support lists
        
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
    """)
    @Get("/markdown")
    fun markdown(
        @Description("""
        `Param` description
        """)
        @Param param1: String,
        @Param param2: String,
        @Description("param3 description")
        @Param param3: MarkdownEnumParam
    ): MarkdownDescriptionResult {
        return MarkdownDescriptionResult(
                result1 = "foo",
                result2 = "bar",
                result3 = "hello"
        )
    }

    @Description("""
        ## Structs description
        ### Structs description subtitle
        > support quotes
    """)
    @ResponseObject
    data class MarkdownDescriptionResult(
        @field:Description("result1 description (default)")
        val result1: String,
        @field:Description("`result2` **description** (use markdown)")
        val result2: String,
        @field:Description("`result3` see https://armeria.dev/ (add links)")
        val result3: String
    )

    @Description("MarkdownEnumParam")
    enum class MarkdownEnumParam {
        @field:Description("Description for `ENUM_1`")
        ENUM_1,
        ENUM_2,
        ENUM_3
    }
}
