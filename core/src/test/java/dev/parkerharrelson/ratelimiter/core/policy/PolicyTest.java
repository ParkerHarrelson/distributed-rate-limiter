package dev.parkerharrelson.ratelimiter.core.policy;

import dev.parkerharrelson.ratelimiter.core.Limit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyTest {

    private static final Limit PER_SEC = Limit.of(10, Duration.ofSeconds(1));
    private static final Limit PER_MIN = Limit.of(100, Duration.ofMinutes(1));

    @Test
    void resolvesEveryScopeIntoTheRightKey() {
        Policy p = new Policy(List.of(
                Rule.of("u", Scope.USER, PER_MIN),
                Rule.of("e", Scope.ENDPOINT, PER_SEC),
                Rule.of("ue", Scope.USER_ENDPOINT, PER_SEC),
                Rule.of("g", Scope.GLOBAL, PER_SEC)));

        assertThat(p.resolve("alice", "/api/cheap")).containsExactly(
                new Check("u", "u:alice", PER_MIN),
                new Check("e", "e:/api/cheap", PER_SEC),
                new Check("ue", "ue:alice:/api/cheap", PER_SEC),
                new Check("g", "g", PER_SEC));
    }

    @Test
    void filtersByEndpointAndUserPatterns() {
        Policy p = new Policy(List.of(
                new Rule("expensive", Scope.USER, List.of("/api/expensive"), List.of(), PER_SEC),
                new Rule("vip", Scope.USER, List.of(), List.of("vip-*"), PER_MIN),
                new Rule("admin-api", Scope.ENDPOINT, List.of("/admin/*"), List.of(), PER_SEC)));

        assertThat(rules(p, "alice", "/api/cheap")).isEmpty();
        assertThat(rules(p, "alice", "/api/expensive")).containsExactly("expensive");
        assertThat(rules(p, "vip-bob", "/api/cheap")).containsExactly("vip");
        assertThat(rules(p, "vip-bob", "/api/expensive")).containsExactly("expensive", "vip");
        assertThat(rules(p, "alice", "/admin/users")).containsExactly("admin-api");
        assertThat(rules(p, "alice", "/administrator")).isEmpty();
    }

    @Test
    void rejectsInvalidRules() {
        assertThatThrownBy(() -> new Policy(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rule.of("", Scope.USER, PER_SEC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rule.of("a:b", Scope.USER, PER_SEC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scope.parse("tenant")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Limit.of(0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Policy(List.of(Rule.of("x", Scope.USER, PER_SEC), Rule.of("x", Scope.GLOBAL, PER_SEC))))
                .hasMessageContaining("duplicate");
    }

    @Test
    void parsesYaml() {
        PolicyFile f = PolicyLoader.parse("""
                algorithm: token_bucket
                fail_mode: open
                rules:
                  - name: user-global
                    scope: user
                    limit: { rate: 50, period: 10s, burst: 20 }
                  - name: expensive
                    scope: user_endpoint
                    endpoints: [/api/expensive]
                    limit: { rate: 5, period: 1m }
                """);
        assertThat(f.algorithm()).isEqualTo("token_bucket");
        assertThat(f.failMode()).isEqualTo("open");
        List<Rule> rules = f.policy().rules();
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).limit()).isEqualTo(new Limit(50, Duration.ofSeconds(10), 20));
        assertThat(rules.get(1).limit()).isEqualTo(Limit.of(5, Duration.ofMinutes(1)));
        assertThat(rules.get(1).endpoints()).containsExactly("/api/expensive");
    }

    @Test
    void yamlErrorsNameTheRule() {
        assertThatThrownBy(() -> PolicyLoader.parse("rules:\n  - name: x\n    scope: user\n    limit: {rate: 1, period: soon}\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule 'x'")
                .hasMessageContaining("soon");
    }

    private static List<String> rules(Policy p, String user, String endpoint) {
        return p.resolve(user, endpoint).stream().map(Check::rule).toList();
    }
}
