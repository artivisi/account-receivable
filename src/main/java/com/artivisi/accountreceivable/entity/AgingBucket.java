package com.artivisi.accountreceivable.entity;

/** Aging buckets by days past due. CURRENT = not yet due. */
public enum AgingBucket {
    CURRENT,
    DUE_1_30,
    DUE_31_60,
    DUE_61_90,
    DUE_90_PLUS;

    public static AgingBucket of(long daysOverdue) {
        if (daysOverdue <= 0) {
            return CURRENT;
        }
        if (daysOverdue <= 30) {
            return DUE_1_30;
        }
        if (daysOverdue <= 60) {
            return DUE_31_60;
        }
        if (daysOverdue <= 90) {
            return DUE_61_90;
        }
        return DUE_90_PLUS;
    }
}
