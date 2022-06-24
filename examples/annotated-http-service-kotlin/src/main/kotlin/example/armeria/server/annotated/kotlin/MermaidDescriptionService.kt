package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.annotation.Description
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Markup
import com.linecorp.armeria.server.annotation.Param

@CoroutineNameDecorator(name = "default")
class MermaidDescriptionService {
    @Description(value = """
        gantt
            title A Gantt Diagram
            dateFormat  YYYY-MM-DD
            section Section
            A task           :a1, 2014-01-01, 30d
            Another task     :after a1  , 20d
            section Another
            Task in sec      :2014-01-12  , 12d
            another task      : 24d
    """, markup = Markup.MERMAID)
    @Get("/mermaid")
    fun mermaid(
        @Param param1: String
    ): HttpResponse {
        return HttpResponse.of(200)
    }
}
