package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.common.logging.LogLevel
import com.linecorp.armeria.common.logging.LogWriter
import com.linecorp.armeria.server.AnnotatedServiceBindingBuilder
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.server.kotlin.CoroutineContextService
import com.linecorp.armeria.server.logging.LoggingService
import kotlinx.coroutines.CoroutineName
import org.slf4j.LoggerFactory

fun main() {
    val server = newServer(8080)
    // To append a coroutine name to a thread name
    System.setProperty("kotlinx.coroutines.debug", "on")
    server.closeOnJvmShutdown()

    server.start().join()
    log.info("Doc service at http://127.0.0.1:8080/docs")
}

private fun newServer(port: Int): Server {
    val sb = Server.builder()
    sb.http(port)
    configureServices(sb)
    return sb.build()
}

fun configureServices(sb: ServerBuilder) {
    sb.annotatedService()
        .pathPrefix("/contextAware")
        .decorator(
            CoroutineContextService.newDecorator { ctx ->
                CoroutineName(ctx.config().defaultServiceNaming().serviceName(ctx) ?: "name")
            },
        )
        .applyCommonDecorator()
        .build(ContextAwareService())
        // DecoratingService
        .annotatedService()
        .pathPrefix("/decorating")
        .applyCommonDecorator()
        .build(DecoratingService())
        // DocService
        .serviceUnder("/docs", DocService())
        // MarkdownDescriptionService
        .annotatedService(MarkdownDescriptionService())
        // MermaidDescriptionService
        .annotatedService(MermaidDescriptionService())
}

private fun AnnotatedServiceBindingBuilder.applyCommonDecorator(): AnnotatedServiceBindingBuilder {
    return this
        .decorator(
            LoggingService.builder()
                .logWriter(
                    LogWriter.builder()
                        .requestLogLevel(LogLevel.INFO)
                        .successfulResponseLogLevel(LogLevel.INFO)
                        .build(),
                )
                .newDecorator(),
        )
}

private val log = LoggerFactory.getLogger("Main")
