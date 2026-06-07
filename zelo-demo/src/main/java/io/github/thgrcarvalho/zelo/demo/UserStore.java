package io.github.thgrcarvalho.zelo.demo;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The demo app's "own database" — an in-memory user table. The DELETE loop
 * erases from here; Zelo only orchestrates it.
 */
@Component
public class UserStore {

    private final Map<String, DemoUser> users = new ConcurrentHashMap<>();

    public DemoUser save(DemoUser user) {
        users.put(user.externalId(), user);
        return user;
    }

    public Optional<DemoUser> find(String externalId) {
        return Optional.ofNullable(users.get(externalId));
    }

    public Collection<DemoUser> all() {
        return users.values();
    }

    /** Erase a user. Returns true if one was present. */
    public boolean delete(String externalId) {
        return users.remove(externalId) != null;
    }
}
