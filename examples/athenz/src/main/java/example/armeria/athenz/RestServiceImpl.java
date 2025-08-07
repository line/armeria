package example.armeria.athenz;

import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.athenz.RequiresAthenzRole;

final class RestServiceImpl {

    @RequiresAthenzRole(resource = "greeting", action = "hello")
    @Get("/hello/{name}")
    public String hello(String name) {
        return "Hello, " + name + '!';
    }
}
