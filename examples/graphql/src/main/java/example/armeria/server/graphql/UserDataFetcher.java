package example.armeria.server.graphql;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import example.armeria.server.graphql.UserDataFetcher.User;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

class UserDataFetcher implements DataFetcher<User> {

    final Map<String, User> data = ImmutableMap.<String, User>builder()
                                               .put("1", new User("1", "hero"))
                                               .put("2", new User("2", "human"))
                                               .put("3", new User("3", "droid"))
                                               .build();

    @Override
    public User get(DataFetchingEnvironment environment) throws Exception {
        final String id = environment.getArgument("id");
        return data.get(id);
    }

    static class User {
        private final String id;
        private final String name;

        User(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}