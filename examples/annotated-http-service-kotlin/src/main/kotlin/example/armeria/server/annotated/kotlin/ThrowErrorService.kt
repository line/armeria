package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.server.annotation.Get

class ThrowErrorService {

    @Get("/error")
    suspend fun throwError(): Nothing {
        throw NotImplementedError()
    }
}
