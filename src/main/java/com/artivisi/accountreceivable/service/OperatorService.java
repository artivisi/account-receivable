package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.entity.AppUser;
import com.artivisi.accountreceivable.entity.UserRole;
import com.artivisi.accountreceivable.exception.DuplicateException;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OperatorService {

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public OperatorService(AppUserRepository repository, PasswordEncoder passwordEncoder,
                           AuditService auditService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AppUser> list() {
        return repository.findAll();
    }

    @Transactional
    public AppUser create(String username, String displayName, UserRole role, String password) {
        if (repository.existsByUsername(username)) {
            throw new DuplicateException("Username already exists: " + username);
        }
        validatePassword(password);
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user.setMustChangePassword(true);
        AppUser saved = repository.save(user);
        auditService.record("OPERATOR_CREATED", "AppUser", saved.getId(), "username=" + username);
        return saved;
    }

    /** Admin sets a temporary password; the operator must replace it at next login. */
    @Transactional
    public void resetPassword(String id, String temporaryPassword) {
        validatePassword(temporaryPassword);
        AppUser user = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        auditService.record("OPERATOR_PASSWORD_RESET", "AppUser", id, "username=" + user.getUsername());
    }

    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        AppUser user = repository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found: " + username));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("Current password is incorrect");
        }
        validatePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        auditService.record("OPERATOR_PASSWORD_CHANGED", "AppUser", user.getId(),
                "username=" + user.getUsername());
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12) {
            throw new InvalidRequestException("Password must be at least 12 characters");
        }
    }

    @Transactional
    public void setEnabled(String id, boolean enabled) {
        AppUser user = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        user.setEnabled(enabled);
        auditService.record("OPERATOR_" + (enabled ? "ENABLED" : "DISABLED"), "AppUser", id,
                "username=" + user.getUsername());
    }
}
