package io.github.thgrcarvalho.zelo.demo;

import io.github.thgrcarvalho.zelo.starter.ZeloClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Map;

/**
 * The demo app's user API. Create users (holding PII), and trigger the LGPD
 * deletion loop via Zelo.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserStore users;
    private final ZeloClient zelo;

    public UserController(UserStore users, ZeloClient zelo) {
        this.users = users;
        this.zelo = zelo;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DemoUser create(@RequestBody CreateUserRequest request) {
        return users.save(new DemoUser(request.externalId(), request.name(), request.email()));
    }

    @GetMapping
    public Collection<DemoUser> list() {
        return users.all();
    }

    @GetMapping("/{externalId}")
    public DemoUser get(@PathVariable String externalId) {
        return users.find(externalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
    }

    /** Ask Zelo to start a deletion request; Zelo will webhook us back to erase the user. */
    @PostMapping("/{externalId}/request-deletion")
    public Map<String, String> requestDeletion(@PathVariable String externalId) {
        String requestId = zelo.requestDeletion(externalId);
        return Map.of("requestId", requestId);
    }

    public record CreateUserRequest(String externalId, String name, String email) {
    }
}
