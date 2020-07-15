package example.armeria.server.servlet;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.servlet.ServletBuilder;

public final class Main {

    public static void main(String[] args) throws Exception {
        getServerBuilder(Server.builder()).build().start().join();
    }

    public static ServerBuilder getServerBuilder(ServerBuilder serverBuilder) {
        serverBuilder.http(8080);
        new ServletBuilder(serverBuilder.http(8080), "/app")
                .servlet("home", new HomeServlet(), "/home")
                .build();
        return serverBuilder;
    }

    private Main() {}
}
