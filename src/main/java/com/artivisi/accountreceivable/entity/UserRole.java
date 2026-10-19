package com.artivisi.accountreceivable.entity;

/**
 * ADMIN manages operators on top of everything OPERATOR does. VIEWER reads every admin page and
 * changes nothing: it exists for people outside finance (an upstream application's team, an
 * auditor) who need to look a bill up without being able to cancel, write off or book anything.
 */
public enum UserRole {
    ADMIN,
    OPERATOR,
    VIEWER
}
