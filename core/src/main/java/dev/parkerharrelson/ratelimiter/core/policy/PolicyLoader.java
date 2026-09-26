package dev.parkerharrelson.ratelimiter.core.policy;

import dev.parkerharrelson.ratelimiter.core.Durations;
import dev.parkerharrelson.ratelimiter.core.Limit;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the limits YAML. Deliberately maps by hand rather than binding into beans so the file
 * format is validated with useful error messages and stays independent of any framework.
 *
 * <pre>
 * algorithm: token_bucket
 * fail_mode: open
 * rules:
 *   - name: user-global
 *     scope: user
 *     endpoints: [/api/expensive]    # optional
 *     users: [vip-*]                 # optional
 *     limit: { rate: 50, period: 10s, burst: 20 }
 * </pre>
 */
public final class PolicyLoader {

    private PolicyLoader() {
    }

    public static PolicyFile load(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("policy: cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static PolicyFile parse(String yamlText) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        if (!(root instanceof Map<?, ?> doc)) {
            throw new IllegalArgumentException("policy: top level must be a mapping");
        }
        String algorithm = str(doc.get("algorithm"), "");
        String failMode = str(doc.get("fail_mode"), "");
        Object rulesNode = doc.get("rules");
        if (!(rulesNode instanceof List<?> ruleList)) {
            throw new IllegalArgumentException("policy: 'rules' must be a list");
        }
        List<Rule> rules = new ArrayList<>();
        for (Object o : ruleList) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("policy: each rule must be a mapping, got " + o);
            }
            String name = str(m.get("name"), "");
            try {
                Object limitNode = m.get("limit");
                if (!(limitNode instanceof Map<?, ?> lm)) {
                    throw new IllegalArgumentException("'limit' must be a mapping");
                }
                Limit limit = new Limit(
                        intOf(lm.get("rate"), "rate"),
                        Durations.parse(str(lm.get("period"), "")),
                        lm.get("burst") == null ? 0 : intOf(lm.get("burst"), "burst"));
                rules.add(new Rule(name, Scope.parse(str(m.get("scope"), "")),
                        strings(m.get("endpoints")), strings(m.get("users")), limit));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("policy: rule '" + name + "': " + e.getMessage(), e);
            }
        }
        return new PolicyFile(algorithm, failMode, new Policy(rules));
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }

    private static int intOf(Object o, String field) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException("'" + field + "' must be an integer, got " + o);
    }

    private static List<String> strings(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(o));
    }
}
