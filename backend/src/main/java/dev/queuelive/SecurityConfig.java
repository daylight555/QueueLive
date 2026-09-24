package dev.queuelive;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean UserDetailsService users(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT * FROM staff WHERE username = ?", (rs, row) ->
            User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                .roles("STAFF").disabled(!rs.getBoolean("enabled")).build(), username)
            .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        // Default session-backed CSRF tokens are fetched through /api/session.
        return http.authorizeHttpRequests(a -> a
                .requestMatchers("/api/staff/**").hasRole("STAFF")
                .requestMatchers("/api/session", "/api/public", "/api/student/**", "/api/events", "/api/login", "/api/logout", "/error").permitAll()
                .anyRequest().denyAll())
            .formLogin(f -> f.loginProcessingUrl("/api/login")
                .successHandler((req, res, auth) -> res.setStatus(204))
                .failureHandler((req, res, ex) -> error(res, 401, "Invalid username or password")))
            .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> error(res, 401, "Staff login required"))
                .accessDeniedHandler((req, res, ex) -> error(res, 403, "Access denied or expired CSRF token; refresh and retry")))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .build();
    }

    static void error(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
