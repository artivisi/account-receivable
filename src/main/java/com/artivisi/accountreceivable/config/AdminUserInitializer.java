package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.entity.AppUser;
import com.artivisi.accountreceivable.entity.UserRole;
import com.artivisi.accountreceivable.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates the bootstrap admin user from {@link ArAdminProperties} on startup if it does not exist.
 * Config-driven, not seed data — a deployment must supply the credentials.
 */
@Configuration
public class AdminUserInitializer {

    private static final Logger log = LoggerFactory.getLogger(AdminUserInitializer.class);

    @Bean
    public ApplicationRunner bootstrapAdmin(AppUserRepository repository,
                                            PasswordEncoder passwordEncoder,
                                            ArAdminProperties properties) {
        return args -> {
            if (repository.existsByUsername(properties.username())) {
                return;
            }
            AppUser admin = new AppUser();
            admin.setUsername(properties.username());
            admin.setPasswordHash(passwordEncoder.encode(properties.password()));
            admin.setDisplayName("Administrator");
            admin.setRole(UserRole.ADMIN);
            admin.setEnabled(true);
            admin.setMustChangePassword(true);
            repository.save(admin);
            log.info("Bootstrap admin user '{}' created", properties.username());
        };
    }
}
