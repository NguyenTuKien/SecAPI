package com.messenger.mini_messenger.service.impl;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.messenger.mini_messenger.dto.request.EncryptedConversationKeyRequest;
import com.messenger.mini_messenger.dto.request.StoreConversationKeysRequest;
import com.messenger.mini_messenger.entity.Conversation;
import com.messenger.mini_messenger.entity.ConversationKeyVersion;
import com.messenger.mini_messenger.entity.ConversationMember;
import com.messenger.mini_messenger.entity.MasterKey;
import com.messenger.mini_messenger.entity.User;
import com.messenger.mini_messenger.enums.ConversationKeyRecipientType;
import com.messenger.mini_messenger.enums.ConversationMemberStatus;
import com.messenger.mini_messenger.exception.ApiException;
import com.messenger.mini_messenger.mapper.ConversationMapper;
import com.messenger.mini_messenger.mapper.UserMapper;
import com.messenger.mini_messenger.repository.ConversationKeyBackupRepository;
import com.messenger.mini_messenger.repository.ConversationKeyVersionRepository;
import com.messenger.mini_messenger.repository.ConversationMemberRepository;
import com.messenger.mini_messenger.repository.ConversationRepository;
import com.messenger.mini_messenger.repository.MasterKeyRepository;
import com.messenger.mini_messenger.repository.UserRepository;
import com.messenger.mini_messenger.repository.UserSessionRepository;
import com.messenger.mini_messenger.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationMemberRepository memberRepository;

    @Mock
    private ConversationKeyVersionRepository keyVersionRepository;

    @Mock
    private ConversationKeyBackupRepository keyBackupRepository;

    @Mock
    private MasterKeyRepository masterKeyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionRepository sessionRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ConversationServiceImpl conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationServiceImpl(
                conversationRepository,
                memberRepository,
                keyVersionRepository,
                keyBackupRepository,
                masterKeyRepository,
                userRepository,
                sessionRepository,
                new ConversationMapper(new UserMapper()),
                redisTemplate,
                JsonMapper.builder().findAndAddModules().build(),
                eventPublisher
        );
    }

    @Test
    void rejectsMasterConversationKeyForDifferentUser() {
        UUID ownerId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID masterKeyId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId, recipientId);
        ConversationKeyVersion keyVersion = keyVersion(conversation, 1);
        MasterKey masterKey = masterKey(UUID.randomUUID(), masterKeyId);
        var request = new StoreConversationKeysRequest(
                1,
                "TEST",
                List.of(new EncryptedConversationKeyRequest(
                        recipientId,
                        ConversationKeyRecipientType.MASTER,
                        masterKeyId,
                        "ZW5jcnlwdGVkLWtleQ==",
                        1
                ))
        );

        when(memberRepository.findByConversationIdAndUserIdAndStatus(
                conversationId,
                ownerId,
                ConversationMemberStatus.ACTIVE
        )).thenReturn(Optional.of(new ConversationMember()));
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(keyVersionRepository.findByConversationIdAndKeyVersion(conversationId, 1)).thenReturn(Optional.of(keyVersion));
        when(masterKeyRepository.findById(masterKeyId)).thenReturn(Optional.of(masterKey));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> conversationService.storeKeys(new CurrentUser(ownerId, "owner", UUID.randomUUID()), conversationId, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(keyBackupRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsEncryptedKeyForNonMember() {
        UUID ownerId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID masterKeyId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId, ownerId);
        ConversationKeyVersion keyVersion = keyVersion(conversation, 1);
        var request = new StoreConversationKeysRequest(
                1,
                "TEST",
                List.of(new EncryptedConversationKeyRequest(
                        recipientId,
                        ConversationKeyRecipientType.MASTER,
                        masterKeyId,
                        "ZW5jcnlwdGVkLWtleQ==",
                        1
                ))
        );

        when(memberRepository.findByConversationIdAndUserIdAndStatus(
                conversationId,
                ownerId,
                ConversationMemberStatus.ACTIVE
        )).thenReturn(Optional.of(new ConversationMember()));
        when(memberRepository.findByConversationIdAndUserIdAndStatus(
                conversationId,
                recipientId,
                ConversationMemberStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(keyVersionRepository.findByConversationIdAndKeyVersion(conversationId, 1)).thenReturn(Optional.of(keyVersion));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> conversationService.storeKeys(new CurrentUser(ownerId, "owner", UUID.randomUUID()), conversationId, request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(masterKeyRepository, never()).findById(masterKeyId);
    }

    private Conversation conversation(UUID conversationId, UUID memberId) {
        User memberUser = new User();
        memberUser.setId(memberId);
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        ConversationMember member = new ConversationMember();
        member.setConversation(conversation);
        member.setUser(memberUser);
        member.setStatus(ConversationMemberStatus.ACTIVE);
        conversation.getMembers().add(member);
        return conversation;
    }

    private ConversationKeyVersion keyVersion(Conversation conversation, int version) {
        ConversationKeyVersion keyVersion = new ConversationKeyVersion();
        keyVersion.setConversation(conversation);
        keyVersion.setKeyVersion(version);
        return keyVersion;
    }

    private MasterKey masterKey(UUID userId, UUID masterKeyId) {
        User user = new User();
        user.setId(userId);
        MasterKey masterKey = new MasterKey();
        masterKey.setId(masterKeyId);
        masterKey.setUser(user);
        return masterKey;
    }
}
