package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.repository.AppUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Form-login security for the admin UI. {@code /admin/**} requires authentication; the JSON API and
 * gateway webhook stay open here (API auth is the separate OAuth2 phase; the webhook is
 * HMAC-verified). CSRF protects admin forms but is disabled for the API/webhook endpoints.
 *
 * <p>VIEWER is enforced here, by HTTP method, and not by hiding buttons: every change in the admin
 * UI is a POST, so a VIEWER gets the GET pages and nothing else. The new/edit form pages are GETs
 * that lead only to a POST, so they are refused too rather than shown and then rejected on submit.
 * Hiding the buttons in the templates is only so a VIEWER is not offered what will be refused.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AppUserRepository repository) {
        return username -> repository.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles(u.getRole().name())
                        .disabled(!u.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity http)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/img/**", "/login", "/error").permitAll()
                        .requestMatchers("/api/**", "/webhooks/**", "/actuator/**").permitAll()
                        .requestMatchers("/admin/operators/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/admin/*/new", "/admin/*/*/edit", "/admin/bulk-uploads/**")
                                .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/admin", "/admin/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                        .requestMatchers("/admin", "/admin/**").hasAnyRole("ADMIN", "OPERATOR")
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/webhooks/**"))
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/admin", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll());
        return http.build();
    }
}
