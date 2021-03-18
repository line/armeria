package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.common.logging.LogLevel
import com.linecorp.armeria.server.AnnotatedServiceBindingBuilder
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.server.kotlin.CoroutineContextService
import com.linecorp.armeria.server.kotlin.annotatedService
import com.linecorp.armeria.server.kotlin.buildServer
import com.linecorp.armeria.server.logging.LoggingService
import kotlinx.coroutines.CoroutineName
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

fun main() {
    val server = newServer(8080)
    // To append a coroutine name to a thread name
    System.setProperty("kotlinx.coroutines.debug", "on")
    Runtime.getRuntime().addShutdownHook(
        thread(start = false) {
            server.stop().join()
            log.info("Server has been stopped.")
        }
    )

    server.start().join()
    log.info("Doc service at http://127.0.0.1:8080/docs")
}

fun newServer(port: Int): Server {
    return buildServer {
        http(port)
        annotatedService(ContextAwareService()) {
            pathPrefix("/contextAware")
            decorator(
                CoroutineContextService.newDecorator { ctx ->
                    CoroutineName(ctx.config().defaultServiceName() ?: "name")
                }
            )
            applyCommonDecorator()
        }
        annotatedService(DecoratingService()) {
            pathPrefix("/decorating")
            applyCommonDecorator()
        }
        serviceUnder("/docs", DocService())
    }
}

private fun AnnotatedServiceBindingBuilder.applyCommonDecorator(): AnnotatedServiceBindingBuilder {
    return this
        .decorator(
            LoggingService.builder()
                .requestLogLevel(LogLevel.INFO)
                .successfulResponseLogLevel(LogLevel.INFO)
                .newDecorator()
        )
}

private val log = LoggerFactory.getLogger("Main")
