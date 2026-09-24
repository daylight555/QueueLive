package dev.queuelive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class DemoProvisioner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final boolean enabled;
    private final String password;
    DemoProvisioner(JdbcTemplate jdbc, PasswordEncoder encoder,
        @Value("${queuelive.demo.enabled}") boolean enabled,
        @Value("${queuelive.demo.password}") String password) {
        this.jdbc = jdbc; this.encoder = encoder; this.enabled = enabled; this.password = password;
    }
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Demo password must be at least 12 characters and at most 72 UTF-8 bytes");
        }
        for (String name : new String[]{"alex", "sam"}) {
            jdbc.update("INSERT INTO staff(username, password_hash) VALUES (?, ?) ON CONFLICT (username) DO NOTHING",
                name, encoder.encode(password));
        }
    }
}
