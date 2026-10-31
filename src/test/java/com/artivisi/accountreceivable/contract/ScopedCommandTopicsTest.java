package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The scoped-topic setting is refused when malformed rather than read leniently: a lenient reading opens or closes a topic by accident. */
class ScopedCommandTopicsTest {

    private static final ArContractProperties.Topics SHARED = new ArContractProperties.Topics(
            "invoice-command-v2", "debtor-command-v2", "invoice-event-v2", "payment-event-v2");

    @Test
    void blankMeansNoScopedTopic() {
        assertThat(ScopedCommandTopics.parse("", SHARED)).isEmpty();
    }

    @Test
    void parsesSeveralTopicsAndCodes() {
        assertThat(ScopedCommandTopics.parse("housing=91, 92;library=93", SHARED))
                .containsEntry("housing", Set.of("91", "92"))
                .containsEntry("library", Set.of("93"));
    }

    @Test
    void refusesAMalformedEntry() {
        assertThatThrownBy(() -> ScopedCommandTopics.parse("housing", SHARED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ScopedCommandTopics.parse("housing=", SHARED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ScopedCommandTopics.parse("housing=91,,92", SHARED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesASharedTopic() {
        // Scoping the shared topic would confine every existing producer to one app's types.
        assertThatThrownBy(() -> ScopedCommandTopics.parse("invoice-command-v2=91", SHARED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesATopicListedTwice() {
        assertThatThrownBy(() -> ScopedCommandTopics.parse("housing=91;housing=92", SHARED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void permitsOnlyItsOwnTypes_andSharedPermitsAll() {
        assertThat(ScopedCommandTopics.CommandScope.of("housing", Set.of("91")).permits("40")).isFalse();
        assertThat(ScopedCommandTopics.CommandScope.of("housing", Set.of("91")).permits("91")).isTrue();
        assertThat(ScopedCommandTopics.CommandScope.shared("invoice-command-v2").permits("40")).isTrue();
    }
}
