package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Admin UI user (form login). */
@Getter
@Setter
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    private String username;

    private String passwordHash;

    private String displayName;

    @Enumerated(EnumType.STRING)
    private UserRole role;

    private boolean enabled;

    /** Set on creation and admin reset; the user must set their own password at next login. */
    private boolean mustChangePassword;
}
