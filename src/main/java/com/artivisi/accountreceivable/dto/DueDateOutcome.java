package com.artivisi.accountreceivable.dto;

/**
 * What moving a due date actually achieved — as distinct from what was asked for.
 *
 * <p>Exists because the two are not the same, and the gap is invisible. Moving a deadline restores a
 * VA the sweep retired, which is the ordinary case and the reason the control exists. It cannot
 * restore a VA that a <em>newer bill took over</em>: that number now belongs to the replacement, and
 * the debt with it. The amendment succeeds either way — the date really did move — so a plain
 * "saved" message reads as "the student can pay now" in a case where nobody can.
 *
 * <p>That happened on 2026-08-19: an operator moved a due date to reactivate an expired bill, the
 * screen said the date was updated, and the receivable stayed unpayable because a replacement bill
 * had held its VA number since the 11th and was already paid. Nothing warned him, so the work looked
 * done. The message must say which of these three happened.
 */
public record DueDateOutcome(Kind kind, int restoredVas, String blockingBillNumber, String blockingStatus) {

    public enum Kind {
        /** A retired VA was reactivated. The payer can pay again, which is the point. */
        VA_RESTORED,
        /** No charge exists yet, so there is nothing to restore — open one to create the VA. */
        NO_CHARGE_YET,
        /**
         * The date moved but the receivable is still unpayable: this charge was cancelled because
         * another bill claimed its VA number. Moving a date cannot undo that, and should not — the
         * debt is on the other bill.
         */
        STILL_UNPAYABLE
    }

    public static DueDateOutcome restored(int count) {
        return new DueDateOutcome(Kind.VA_RESTORED, count, null, null);
    }

    public static DueDateOutcome noChargeYet() {
        return new DueDateOutcome(Kind.NO_CHARGE_YET, 0, null, null);
    }

    public static DueDateOutcome stillUnpayable(String blockingBillNumber, String blockingStatus) {
        return new DueDateOutcome(Kind.STILL_UNPAYABLE, 0, blockingBillNumber, blockingStatus);
    }
}
