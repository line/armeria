package example.armeria.server.servlet;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.servlet.ServletBuilder;

public class Main {
    public static void main(String[] args) throws Exception {
        getServerBuilder(Server.builder()).build().start().join();
    }

    public static ServerBuilder getServerBuilder(ServerBuilder serverBuilder) {
        final ServletBuilder sb = new ServletBuilder(
                serverBuilder.http(8080), "/app");
        serverBuilder = sb.servlet("/home", new HomeServlet())
                          .build();
        return serverBuilder;
    }
}
