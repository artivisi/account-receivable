package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.service.contact.PhoneNumbers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SHAPES here were all found in a real debtor register — nobody would think to test a phone
 * number containing the word "WhatsApp", or a pair of invisible Unicode direction marks, until they
 * had seen one. The DIGITS are invented, and deliberately so: every leak this codebase has had
 * arrived as a test fixture, so a real subscriber number does not go in a product repository even a
 * private one. Subscriber parts are sequential and implausible; only the prefixes are realistic,
 * because the prefix is what the rules actually key on.
 */
class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "081300000001                | 081300000001",   // already right, just padded
            "WhatsApp+6287700000002      | 087700000002",   // someone typed the app name in
            "+62 887-0000-00003          | 0887000000003",  // 13 digits, the long end of the range  // spaces and hyphens
            "8110000004                  | 08110000004",    // leading zero missing
            "+626285700000005            | 085700000005",    // country code entered twice
            "0812-0000-0006              | 081200000006",
            "081200000007q               | 081200000007",  // stray letter, number otherwise sound
    })
    void indonesianMobilesBecomePlain08(String raw, String expected) {
        assertThat(PhoneNumbers.normalise(raw.strip())).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+20100000008",      // Egypt — a foreign student, not a typo
            "+249 11 000 0009",  // Sudan
            "+82-10-0000-0010",  // Korea
            "0210000011",        // landline
            "0",
    })
    void anythingNotClearlyAnIndonesianMobileIsLeftExactlyAsFound(String raw) {
        // Mangling a correct foreign number into something that looks Indonesian is worse than
        // leaving it: the result looks valid and silently reaches nobody.
        assertThat(PhoneNumbers.normalise(raw)).isEqualTo(raw);
    }

    @Test
    void nullAndBlankSurviveUntouched() {
        assertThat(PhoneNumbers.normalise(null)).isNull();
        assertThat(PhoneNumbers.normalise("   ")).isEqualTo("   ");
    }
}
