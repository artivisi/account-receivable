package com.artivisi.accountreceivable.service.contact;

/**
 * Puts an Indonesian mobile number into the one shape downstream systems accept, and leaves
 * everything else exactly as it was found.
 *
 * <p>Numbers reach us from registration forms filled in by hand, so they arrive as
 * {@code +62 812-0000-0006}, as {@code WhatsApp+628770000002}, wrapped in Unicode direction marks
 * that a phone keyboard inserted invisibly, or with the leading zero missing. An SMS gateway takes
 * none of those. Nearly every message the notification hub has rendered carried a plain
 * {@code 08…} number — that is the shape known to work, so it is the shape we store.
 *
 * <p><b>What it deliberately does NOT touch.</b> A number it cannot confidently read as an
 * Indonesian mobile is returned unchanged: foreign numbers (students from Sudan, Malaysia, Egypt and
 * Korea are all in the register), landlines, and outright junk. Those are not errors to be
 * corrected — mangling {@code +20100000008} into something that looks Indonesian would turn a
 * correct foreign number into a wrong local one, which is worse than leaving it alone and worse than
 * an empty field, because it looks valid.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /**
     * @return the number as {@code 08…} when it is unambiguously an Indonesian mobile, otherwise the
     *         input unchanged (including null and blank)
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        // An explicit "+" means whoever typed it supplied a country code. Honour it: unless that
        // code is Indonesia's, this is a foreign number and none of the rules below apply. Without
        // this, "+82-10-0000-0010" — a Korean student's number — is nine digits after a leading 8
        // and gets "corrected" into 0821000000010, which looks perfectly Indonesian and reaches
        // nobody. Length alone cannot tell the two apart.
        if (raw.indexOf('+') >= 0 && !digits.startsWith("62")) {
            return raw;
        }
        // A country code typed twice — "+62 628…" — which happens when a form prefills +62 and the
        // person pastes a number that already carries it.
        if (digits.startsWith("62") && digits.length() > 12) {
            String withoutCountry = digits.substring(2);
            if (withoutCountry.startsWith("62")) {
                digits = withoutCountry;
            }
        }
        if (digits.matches("62 8[0-9]{8,11}".replace(" ", ""))) {
            return "0" + digits.substring(2);
        }
        if (digits.matches("08[0-9]{8,11}")) {
            return digits;
        }
        // Missing leading zero: "8110000004". Only when the length works out as a mobile number,
        // otherwise this would capture foreign numbers that merely begin with 8.
        if (digits.matches("8[0-9]{8,11}")) {
            return "0" + digits;
        }
        return raw;
    }
}
