package com.messenger.mini_messenger.service.impl;

import com.messenger.mini_messenger.dto.request.MasterKeyRequest;
import com.messenger.mini_messenger.dto.request.UpdateUserRequest;
import com.messenger.mini_messenger.entity.AuthIdentity;
import com.messenger.mini_messenger.entity.MasterKey;
import com.messenger.mini_messenger.entity.User;
import com.messenger.mini_messenger.enums.AuthProvider;
import com.messenger.mini_messenger.exception.ApiException;
import com.messenger.mini_messenger.mapper.MasterKeyMapper;
import com.messenger.mini_messenger.mapper.UserMapper;
import com.messenger.mini_messenger.repository.AuthIdentityRepository;
import com.messenger.mini_messenger.repository.MasterKeyRepository;
import com.messenger.mini_messenger.repository.UserRepository;
import com.messenger.mini_messenger.repository.UserSessionRepository;
import com.messenger.mini_messenger.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MasterKeyRepository masterKeyRepository;

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private AuthIdentityRepository authIdentityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(
                userRepository,
                masterKeyRepository,
                userSessionRepository,
                authIdentityRepository,
                passwordEncoder,
                new UserMapper(),
                new MasterKeyMapper()
        );
    }

    @Test
    void updatesEmailDisplayNameAvatarPasswordAndEncryptedMasterKey() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        AuthIdentity identity = localIdentity(user);
        MasterKeyRequest newMasterKey = new MasterKeyRequest(
                "bmV3LXB1YmxpYw==",
                "bmV3LWVuY3J5cHRlZC1wcml2YXRl",
                "bmV3LWl2",
                "bmV3LXNhbHQ=",
                Map.of("algo", "PBKDF2", "iterations", 210000)
        );
        UpdateUserRequest request = new UpdateUserRequest(
                "new@example.com",
                "New Display",
                "https://cdn.example.com/avatar.png",
                "old-password",
                "new-password-123",
                newMasterKey
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(authIdentityRepository.findByUserIdAndProvider(userId, AuthProvider.LOCAL)).thenReturn(Optional.of(identity));
        when(passwordEncoder.matches("old-password", "$old")).thenReturn(true);
        when(passwordEncoder.encode("new-password-123")).thenReturn("$new");

        var response = userService.updateMe(new CurrentUser(userId, "user", UUID.randomUUID()), request);

        assertEquals("new@example.com", user.getEmail());
        assertEquals("New Display", user.getDisplayName());
        assertEquals("https://cdn.example.com/avatar.png", user.getAvatarUrl());
        assertEquals("new@example.com", identity.getProviderEmail());
        assertEquals("$new", identity.getPasswordHash());
        assertEquals("bmV3LXB1YmxpYw==", user.getMasterKey().getPublicKey());
        assertEquals("bmV3LWVuY3J5cHRlZC1wcml2YXRl", user.getMasterKey().getEncryptedPrivateKey());
        assertNotNull(user.getMasterKey().getRotatedAt());
        assertEquals(userId, response.id());
    }

    @Test
    void rejectsDuplicateEmailOnUpdate() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        UpdateUserRequest request = new UpdateUserRequest(
                "taken@example.com",
                null,
                null,
                null,
                null,
                null
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> userService.updateMe(new CurrentUser(userId, "user", UUID.randomUUID()), request)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void rejectsBlankEmailOnUpdate() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        UpdateUserRequest request = new UpdateUserRequest(
                "   ",
                null,
                null,
                null,
                null,
                null
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> userService.updateMe(new CurrentUser(userId, "user", UUID.randomUUID()), request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getMessage().contains("Email"));
    }

    @Test
    void rejectsPasswordChangeWhenCurrentPasswordIsWrong() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        AuthIdentity identity = localIdentity(user);
        UpdateUserRequest request = new UpdateUserRequest(
                null,
                null,
                null,
                "wrong-password",
                "new-password-123",
                null
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(authIdentityRepository.findByUserIdAndProvider(userId, AuthProvider.LOCAL)).thenReturn(Optional.of(identity));
        when(passwordEncoder.matches("wrong-password", "$old")).thenReturn(false);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> userService.updateMe(new CurrentUser(userId, "user", UUID.randomUUID()), request)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertTrue(exception.getMessage().contains("Current password"));
    }

    private User user(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user");
        user.setEmail("old@example.com");
        user.setDisplayName("Old Display");
        MasterKey masterKey = new MasterKey();
        masterKey.setUser(user);
        masterKey.setPublicKey("b2xkLXB1YmxpYw==");
        masterKey.setEncryptedPrivateKey("b2xkLWVuY3J5cHRlZC1wcml2YXRl");
        masterKey.setPrivateKeyIv("b2xkLWl2");
        masterKey.setPinSalt("b2xkLXNhbHQ=");
        masterKey.setKdfParams(Map.of("algo", "PBKDF2"));
        user.setMasterKey(masterKey);
        return user;
    }

    private AuthIdentity localIdentity(User user) {
        AuthIdentity identity = new AuthIdentity();
        identity.setUser(user);
        identity.setProvider(AuthProvider.LOCAL);
        identity.setProviderSubject(user.getUsername());
        identity.setProviderEmail(user.getEmail());
        identity.setPasswordHash("$old");
        return identity;
    }
}
