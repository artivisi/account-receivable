package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Redirects an authenticated user with a pending forced password change to /change-password
 * before any other page. Runs after the security filter chain, so the authentication is present.
 */
@Component
public class ForcePasswordChangeFilter extends OncePerRequestFilter {

    private final AppUserRepository repository;

    public ForcePasswordChangeFilter(AppUserRepository repository) {
        this.repository = repository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/change-password") || uri.equals("/login") || uri.equals("/logout")
                || uri.equals("/error")
                || uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/img/")
                || uri.startsWith("/api/") || uri.startsWith("/webhooks/") || uri.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && repository.findByUsername(auth.getName())
                        .map(u -> u.isMustChangePassword()).orElse(false)) {
            response.sendRedirect("/change-password");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
