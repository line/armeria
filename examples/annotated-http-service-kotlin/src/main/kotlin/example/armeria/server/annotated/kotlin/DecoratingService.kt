package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.DecoratorFactory
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.kotlin.CoroutineContextService
import kotlinx.coroutines.CoroutineName
import org.slf4j.LoggerFactory
import java.util.function.Function
import kotlin.coroutines.coroutineContext

@CoroutineNameDecorator(name = "default")
class DecoratingService {

    @Get("/foo")
    suspend fun foo(): HttpResponse {
        log.info("My name is ${coroutineContext[CoroutineName]?.name}")
        return HttpResponse.of("OK")
    }

    @CoroutineNameDecorator(name = "bar")
    @Get("/bar")
    suspend fun bar(): HttpResponse {
        log.info("My name is ${coroutineContext[CoroutineName]?.name}")
        return HttpResponse.of("OK")
    }

    @Blocking
    @CoroutineNameDecorator(name = "blocking")
    @Get("/blocking")
    suspend fun blocking(): HttpResponse {
        log.info("My name is ${coroutineContext[CoroutineName]?.name}")

        assert(Thread.currentThread().name.contains("armeria-common-blocking-tasks"))
        return HttpResponse.of("OK")
    }

    companion object {
        private val log = LoggerFactory.getLogger(DecoratingService::class.java)
    }
}

@DecoratorFactory(CoroutineNameDecoratorFactory::class)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CoroutineNameDecorator(val name: String)

class CoroutineNameDecoratorFactory : DecoratorFactoryFunction<CoroutineNameDecorator> {
    override fun newDecorator(parameter: CoroutineNameDecorator): Function<in HttpService, out HttpService> {
        return CoroutineContextService.newDecorator {
            CoroutineName(name = parameter.name)
        }
    }
}
