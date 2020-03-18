package example.springframework.boot.minimal.kotlin

import com.linecorp.armeria.server.annotation.ExceptionHandler
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import javax.validation.constraints.Size
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * Note that this is not a Spring-based component but an annotated HTTP service that leverages
 * Armeria's built-in annotations.
 *
 * @see [Annotated HTTP Service](https://line.github.io/armeria/docs/server-annotated-service)
 */
@Component
@Validated
@ExceptionHandler(ValidationExceptionHandler::class)
class HelloAnnotatedService {

    @Get("/")
    fun defaultHello(): String = "Hello, world! Try sending a GET request to /hello/armeria"

    /**
     * An example in order to show how to use validation framework in an annotated HTTP service.
     */
    @Get("/hello/{name}")
    fun hello(
        @Param
        @Size(min = 3, max = 10, message = "name should have between 3 and 10 characters")
        name: String
    ): String = "Hello, $name! This message is from Armeria annotated service!"
}
