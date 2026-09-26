package dev.parkerharrelson.ratelimiter.core.redis;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.LimiterUnavailableException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Loads a Lua script from the classpath and runs it with {@code EVALSHA}, falling back to
 * {@code EVAL} the first time Redis reports it has not seen the script.
 *
 * <p>Every limiter script in this package returns the same 5-element integer array,
 * <pre>{ allowed(0|1), limit, remaining, retry_after_ms, reset_after_ms }</pre>
 * so the Java wrappers are interchangeable and the algorithm lives entirely in Lua.
 */
final class RedisScript {

    private final String name;
    private final String source;
    private final String sha1;

    RedisScript(String resourceName) {
        this.name = resourceName;
        this.source = read("/lua/" + resourceName);
        this.sha1 = sha1(source);
    }

    Decision run(RedisCommands<String, String> redis, String key, Object... args) {
        String[] keys = {key};
        String[] argv = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            argv[i] = String.valueOf(args[i]);
        }
        List<Object> reply;
        try {
            try {
                reply = redis.evalsha(sha1, ScriptOutputType.MULTI, keys, argv);
            } catch (RedisNoScriptException e) {
                reply = redis.eval(source, ScriptOutputType.MULTI, keys, argv);
            }
        } catch (RedisCommandExecutionException e) {
            if (e.getMessage() != null && e.getMessage().contains("NOT_IMPLEMENTED")) {
                throw new ExerciseNotImplementedException(name);
            }
            throw new LimiterUnavailableException("redis script " + name + " failed: " + e.getMessage(), e);
        } catch (RedisException e) {
            // Timeouts, connection refused, partitions: all "could not decide".
            throw new LimiterUnavailableException("redis unavailable: " + e.getMessage(), e);
        }
        return decode(reply);
    }

    private Decision decode(List<Object> reply) {
        if (reply == null || reply.size() != 5) {
            throw new LimiterUnavailableException("script " + name + " returned " + (reply == null ? "nil" : reply.size() + " values") + ", want 5");
        }
        long[] v = new long[5];
        for (int i = 0; i < 5; i++) {
            if (!(reply.get(i) instanceof Long l)) {
                throw new LimiterUnavailableException("script " + name + " reply[" + i + "] is " + reply.get(i) + ", want integer");
            }
            v[i] = l;
        }
        boolean allowed = v[0] == 1;
        int limit = (int) v[1];
        return allowed
                ? Decision.allow(limit, (int) v[2], Duration.ofMillis(v[4]))
                : Decision.deny(limit, Duration.ofMillis(v[3]), Duration.ofMillis(v[4]));
    }

    private static String read(String path) {
        try (InputStream in = RedisScript.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("reading " + path, e);
        }
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
