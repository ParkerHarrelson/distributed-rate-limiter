package dev.parkerharrelson.ratelimiter.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Demo endpoints to rate limit. Running on virtual threads, so the sleep below is cheap. */
@RestController
public class ApiController {

    @GetMapping("/api/cheap")
    public Map<String, Object> cheap() {
        return Map.of("ok", true, "cost", "cheap");
    }

    @GetMapping("/api/expensive")
    public Map<String, Object> expensive() throws InterruptedException {
        Thread.sleep(20);
        return Map.of("ok", true, "cost", "expensive");
    }

    @GetMapping("/api/echo/{name}")
    public Map<String, Object> echo(@PathVariable String name) {
        return Map.of("ok", true, "name", name);
    }
}
