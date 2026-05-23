package com.messenger.mini_messenger.config;

import com.messenger.mini_messenger.entity.AuthIdentity;
import com.messenger.mini_messenger.entity.MasterKey;
import com.messenger.mini_messenger.entity.User;
import com.messenger.mini_messenger.enums.AuthProvider;
import com.messenger.mini_messenger.enums.UserRole;
import com.messenger.mini_messenger.enums.UserStatus;
import com.messenger.mini_messenger.repository.UserRepository;
import com.messenger.mini_messenger.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private final boolean enabled;
    private final String username;
    private final String password;
    private final String email;
    private final String publicKey;
    private final String encryptedPrivateKey;
    private final String privateKeyIv;
    private final String pinSalt;
    private final String kdfParams;
    private final JsonUtil jsonUtil;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(
            @Value("${app.bootstrap.admin.enabled:true}") boolean enabled,
            @Value("${app.bootstrap.admin.username:admin}") String username,
            @Value("${app.bootstrap.admin.password:admin123456}") String password,
            @Value("${app.bootstrap.admin.email:admin@local.dev}") String email,
            @Value("${app.bootstrap.admin.master-key.public-key:YWRtaW4tcHVibGljLWtleS1wbGFjZWhvbGRlcg==}") String publicKey,
            @Value("${app.bootstrap.admin.master-key.encrypted-private-key:YWRtaW4tZW5jcnlwdGVkLXByaXZhdGUta2V5LWhvbGRlcg==}") String encryptedPrivateKey,
            @Value("${app.bootstrap.admin.master-key.private-key-iv:YWRtaW4taXY=}") String privateKeyIv,
            @Value("${app.bootstrap.admin.master-key.pin-salt:123456}") String pinSalt,
            @Value("${app.bootstrap.admin.master-key.kdf-params:{\"algorithm\":\"PBKDF2\",\"iterations\":100000,\"keyLength\":256,\"digest\":\"SHA-256\",\"bootstrap\":true}}") String kdfParams,
            JsonUtil jsonUtil,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.email = email;
        this.publicKey = publicKey;
        this.encryptedPrivateKey = encryptedPrivateKey;
        this.privateKeyIv = privateKeyIv;
        this.pinSalt = pinSalt;
        this.kdfParams = kdfParams;
        this.jsonUtil = jsonUtil;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        if (!enabled || userRepository.existsByUsername(username)) {
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setEmail(email);
        admin.setDisplayName("System Admin");
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);

        AuthIdentity identity = new AuthIdentity();
        identity.setUser(admin);
        identity.setProvider(AuthProvider.LOCAL);
        identity.setProviderSubject(username);
        identity.setProviderEmail(email);
        identity.setPasswordHash(passwordEncoder.encode(password));
        identity.setEmailVerified(true);
        admin.getIdentities().add(identity);

        MasterKey masterKey = new MasterKey();
        masterKey.setUser(admin);
        masterKey.setPublicKey(publicKey);
        masterKey.setEncryptedPrivateKey(encryptedPrivateKey);
        masterKey.setPrivateKeyIv(privateKeyIv);
        masterKey.setPinSalt(pinSalt);
        masterKey.setKdfParams(jsonUtil.toMap(kdfParams));
        admin.setMasterKey(masterKey);

        userRepository.save(admin);
    }
}
