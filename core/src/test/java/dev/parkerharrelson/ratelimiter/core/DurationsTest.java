package dev.parkerharrelson.ratelimiter.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurationsTest {

    @Test
    void parsesShortForms() {
        assertThat(Durations.parse("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(Durations.parse("10s")).isEqualTo(Duration.ofSeconds(10));
        assertThat(Durations.parse("1m")).isEqualTo(Duration.ofMinutes(1));
        assertThat(Durations.parse("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(Durations.parse("PT1.5S")).isEqualTo(Duration.ofMillis(1500));
        assertThatThrownBy(() -> Durations.parse("soon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void humanizesRoundTrip() {
        assertThat(Durations.humanize(Duration.ofSeconds(10))).isEqualTo("10s");
        assertThat(Durations.humanize(Duration.ofMinutes(1))).isEqualTo("1m");
        assertThat(Durations.humanize(Duration.ofMillis(250))).isEqualTo("250ms");
        assertThat(new Limit(50, Duration.ofSeconds(10), 20)).hasToString("50/10s (burst 20)");
        assertThat(Limit.of(5, Duration.ofMinutes(1))).hasToString("5/1m");
    }
}
