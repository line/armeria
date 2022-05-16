package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.ProducesJson
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

class ContextAwareService {

    @Get("/foo")
    @ProducesJson
    suspend fun foo(@Param name: String, @Param id: Int): FooResponse {
        log.info("Hello $name")
        // Make sure that current thread is request context aware
        ServiceRequestContext.current()

        // Armeria request context will automatically be propagated to myDispatcher's scope.
        log.info("Start blocking task for $name")
        BusinessLogic.blockingTask()
        log.info("Finished blocking task for $name")

        // Make sure that current thread is request context aware
        ServiceRequestContext.current()

        return FooResponse(id = id, name = name)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ContextAwareService::class.java)

        data class FooResponse(val id: Int, val name: String)
    }
}

// Assume that this logic may be used by non-armeria application
object BusinessLogic {
    // To limit the number of parallel executions
    private val myDispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    suspend fun blockingTask() {
        return withContext(myDispatcher) {
            // Make sure that current thread is request context aware
            // Propagating RequestContext is needed for logging, tracing.
            ServiceRequestContext.current()

            Thread.sleep(1000)
        }
    }
}
