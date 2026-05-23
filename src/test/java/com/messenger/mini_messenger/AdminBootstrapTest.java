package com.messenger.mini_messenger;

import com.messenger.mini_messenger.enums.UserRole;
import com.messenger.mini_messenger.repository.AuthIdentityRepository;
import com.messenger.mini_messenger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_bootstrap_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.docker.compose.enabled=false",
        "app.bootstrap.admin.enabled=true",
        "app.bootstrap.admin.username=admin",
        "app.bootstrap.admin.password=admin123456"
})
class AdminBootstrapTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthIdentityRepository authIdentityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void seedsInitialAdminAccountWithAdminRole() {
        var admin = userRepository.findByUsername("admin").orElseThrow();

        assertEquals(UserRole.ADMIN, admin.getRole());
        assertNotNull(admin.getMasterKey());
        var identity = authIdentityRepository.findByUserIdAndProvider(admin.getId(), com.messenger.mini_messenger.enums.AuthProvider.LOCAL)
                .orElseThrow();
        assertTrue(passwordEncoder.matches("admin123456", identity.getPasswordHash()));
    }
}
