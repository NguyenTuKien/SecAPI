package com.messenger.mini_messenger.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messenger.mini_messenger.dto.redis.RedisConversationKeyValue;
import com.messenger.mini_messenger.dto.request.CreateConversationRequest;
import com.messenger.mini_messenger.dto.request.EncryptedConversationKeyRequest;
import com.messenger.mini_messenger.dto.request.StoreConversationKeysRequest;
import com.messenger.mini_messenger.dto.request.UpdateConversationRequest;
import com.messenger.mini_messenger.dto.event.MemberLeftEvent;
import com.messenger.mini_messenger.dto.response.ApiMessageResponse;
import com.messenger.mini_messenger.dto.response.ConversationResponse;
import com.messenger.mini_messenger.dto.response.EncryptedConversationKeyResponse;
import com.messenger.mini_messenger.entity.Conversation;
import com.messenger.mini_messenger.entity.ConversationKeyBackup;
import com.messenger.mini_messenger.entity.ConversationKeyVersion;
import com.messenger.mini_messenger.entity.ConversationMember;
import com.messenger.mini_messenger.entity.MasterKey;
import com.messenger.mini_messenger.entity.User;
import com.messenger.mini_messenger.entity.UserSession;
import com.messenger.mini_messenger.enums.ConversationKeyRecipientType;
import com.messenger.mini_messenger.enums.ConversationKeyVersionStatus;
import com.messenger.mini_messenger.enums.ConversationMemberRole;
import com.messenger.mini_messenger.enums.ConversationMemberStatus;
import com.messenger.mini_messenger.enums.SessionStatus;
import com.messenger.mini_messenger.exception.ApiException;
import com.messenger.mini_messenger.mapper.ConversationMapper;
import com.messenger.mini_messenger.repository.ConversationKeyBackupRepository;
import com.messenger.mini_messenger.repository.ConversationKeyVersionRepository;
import com.messenger.mini_messenger.repository.ConversationMemberRepository;
import com.messenger.mini_messenger.repository.ConversationRepository;
import com.messenger.mini_messenger.repository.MasterKeyRepository;
import com.messenger.mini_messenger.repository.UserRepository;
import com.messenger.mini_messenger.repository.UserSessionRepository;
import com.messenger.mini_messenger.security.CurrentUser;
import com.messenger.mini_messenger.service.ConversationService;
import com.messenger.mini_messenger.util.RedisKeyUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationKeyVersionRepository keyVersionRepository;
    private final ConversationKeyBackupRepository keyBackupRepository;
    private final MasterKeyRepository masterKeyRepository;
    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final ConversationMapper conversationMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationServiceImpl(
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            ConversationKeyVersionRepository keyVersionRepository,
            ConversationKeyBackupRepository keyBackupRepository,
            MasterKeyRepository masterKeyRepository,
            UserRepository userRepository,
            UserSessionRepository sessionRepository,
            ConversationMapper conversationMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.keyVersionRepository = keyVersionRepository;
        this.keyBackupRepository = keyBackupRepository;
        this.masterKeyRepository = masterKeyRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.conversationMapper = conversationMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public ConversationResponse create(CurrentUser currentUser, CreateConversationRequest request) {
        User creator = findUser(currentUser.userId());
        Conversation conversation = new Conversation();
        conversation.setType(request.type());
        conversation.setName(request.name());
        conversation.setCreatedBy(creator);
        conversation.setCurrentKeyVersion(resolveKeyVersion(request.encryptedKeys()));
        conversationRepository.save(conversation);

        Set<UUID> participantIds = new LinkedHashSet<>(request.participantIds());
        participantIds.add(currentUser.userId());
        for (UUID userId : participantIds) {
            ConversationMember member = new ConversationMember();
            member.setConversation(conversation);
            member.setUser(findUser(userId));
            member.setRole(userId.equals(currentUser.userId()) ? ConversationMemberRole.OWNER : ConversationMemberRole.MEMBER);
            member.setStatus(ConversationMemberStatus.ACTIVE);
            conversation.getMembers().add(member);
        }

        ConversationKeyVersion keyVersion = createKeyVersion(conversation, creator, conversation.getCurrentKeyVersion(), "CREATE_CONVERSATION");
        storeEncryptedKeys(conversation, keyVersion, request.encryptedKeys());
        return conversationMapper.toResponse(conversation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationResponse> list(CurrentUser currentUser) {
        return conversationRepository.findActiveConversationsForUser(currentUser.userId())
                .stream()
                .map(conversationMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationResponse get(CurrentUser currentUser, UUID conversationId) {
        return conversationMapper.toResponse(findAccessibleConversation(currentUser, conversationId));
    }

    @Override
    @Transactional
    public ConversationResponse update(CurrentUser currentUser, UUID conversationId, UpdateConversationRequest request) {
        Conversation conversation = findAccessibleConversation(currentUser, conversationId);
        requireOwner(currentUser, conversation);
        conversation.setName(request.name());
        return conversationMapper.toResponse(conversation);
    }

    @Override
    @Transactional
    public ApiMessageResponse leave(CurrentUser currentUser, UUID conversationId) {
        ConversationMember member = memberRepository.findByConversationIdAndUserIdAndStatus(
                        conversationId,
                        currentUser.userId(),
                        ConversationMemberStatus.ACTIVE
                )
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation membership not found"));
        member.setStatus(ConversationMemberStatus.LEFT);
        member.setLeftAt(Instant.now());
        eventPublisher.publishEvent(new MemberLeftEvent(conversationId, currentUser.userId()));
        return new ApiMessageResponse("Left conversation successfully");
    }

    @Override
    @Transactional
    public ApiMessageResponse storeKeys(CurrentUser currentUser, UUID conversationId, StoreConversationKeysRequest request) {
        Conversation conversation = findAccessibleConversation(currentUser, conversationId);
        ConversationKeyVersion keyVersion = keyVersionRepository.findByConversationIdAndKeyVersion(conversationId, request.newKeyVersion())
                .orElseGet(() -> createKeyVersion(conversation, findUser(currentUser.userId()), request.newKeyVersion(), request.reason()));
        storeEncryptedKeys(conversation, keyVersion, request.encryptedKeys());
        if (request.newKeyVersion() > conversation.getCurrentKeyVersion()) {
            conversation.setCurrentKeyVersion(request.newKeyVersion());
        }
        return new ApiMessageResponse("Conversation keys stored successfully");
    }

    @Override
    @Transactional(readOnly = true)
    public EncryptedConversationKeyResponse getMyEncryptedKey(CurrentUser currentUser, UUID conversationId, Integer keyVersion) {
        Conversation conversation = findAccessibleConversation(currentUser, conversationId);
        int version = keyVersion == null ? conversation.getCurrentKeyVersion() : keyVersion;
        String redisKey = RedisKeyUtil.conversationKey(currentUser.sessionKeyId(), conversationId, version);
        String redisValue = redisTemplate.opsForValue().get(redisKey);
        if (redisValue != null) {
            try {
                RedisConversationKeyValue value = objectMapper.readValue(redisValue, RedisConversationKeyValue.class);
                return new EncryptedConversationKeyResponse(
                        ConversationKeyRecipientType.SESSION,
                        currentUser.sessionKeyId(),
                        value.encryptedConversationKey(),
                        value.keyVersion()
                );
            } catch (Exception exception) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read encrypted conversation key from Redis");
            }
        }
        ConversationKeyBackup backup = keyBackupRepository.findByConversationIdAndUserIdAndKeyVersionKeyVersion(
                        conversationId,
                        currentUser.userId(),
                        version
                )
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Encrypted conversation key not found"));
        return new EncryptedConversationKeyResponse(
                ConversationKeyRecipientType.MASTER,
                backup.getMasterKey().getId(),
                backup.getEncryptedConversationKey(),
                backup.getKeyVersion().getKeyVersion()
        );
    }

    @Override
    @Transactional
    public ApiMessageResponse rotateKeys(CurrentUser currentUser, UUID conversationId, StoreConversationKeysRequest request) {
        Conversation conversation = findAccessibleConversation(currentUser, conversationId);
        requireOwner(currentUser, conversation);
        if (request.newKeyVersion() <= conversation.getCurrentKeyVersion()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New key version must be greater than current key version");
        }
        conversation.setCurrentKeyVersion(request.newKeyVersion());
        ConversationKeyVersion keyVersion = createKeyVersion(conversation, findUser(currentUser.userId()), request.newKeyVersion(), request.reason());
        storeEncryptedKeys(conversation, keyVersion, request.encryptedKeys());
        return new ApiMessageResponse("Conversation key rotated successfully");
    }

    private int resolveKeyVersion(List<EncryptedConversationKeyRequest> encryptedKeys) {
        return encryptedKeys.stream().mapToInt(EncryptedConversationKeyRequest::keyVersion).max().orElse(1);
    }

    private Conversation findAccessibleConversation(CurrentUser currentUser, UUID conversationId) {
        memberRepository.findByConversationIdAndUserIdAndStatus(conversationId, currentUser.userId(), ConversationMemberStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a participant of this conversation"));
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    private void requireOwner(CurrentUser currentUser, Conversation conversation) {
        ConversationMember member = memberRepository.findByConversationIdAndUserIdAndStatus(
                        conversation.getId(),
                        currentUser.userId(),
                        ConversationMemberStatus.ACTIVE
                )
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a participant of this conversation"));
        if (member.getRole() != ConversationMemberRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only owner can update this conversation");
        }
    }

    private ConversationKeyVersion createKeyVersion(Conversation conversation, User creator, int version, String reason) {
        ConversationKeyVersion keyVersion = new ConversationKeyVersion();
        keyVersion.setConversation(conversation);
        keyVersion.setCreatedBy(creator);
        keyVersion.setKeyVersion(version);
        keyVersion.setReason(reason);
        keyVersion.setStatus(ConversationKeyVersionStatus.ACTIVE);
        return keyVersionRepository.save(keyVersion);
    }

    private void storeEncryptedKeys(Conversation conversation, ConversationKeyVersion keyVersion, List<EncryptedConversationKeyRequest> encryptedKeys) {
        for (EncryptedConversationKeyRequest encryptedKey : encryptedKeys) {
            validateEncryptedKeyRequest(conversation, keyVersion, encryptedKey);
            if (encryptedKey.recipientType() == ConversationKeyRecipientType.MASTER) {
                MasterKey masterKey = findMasterKeyForUser(encryptedKey);
                ConversationKeyBackup backup = keyBackupRepository.findByConversationIdAndUserIdAndKeyVersionKeyVersion(
                                conversation.getId(),
                                encryptedKey.userId(),
                                keyVersion.getKeyVersion()
                        )
                        .orElseGet(() -> {
                            ConversationKeyBackup newBackup = new ConversationKeyBackup();
                            newBackup.setConversation(conversation);
                            newBackup.setKeyVersion(keyVersion);
                            newBackup.setUser(masterKey.getUser());
                            newBackup.setMasterKey(masterKey);
                            return newBackup;
                        });
                backup.setEncryptedConversationKey(encryptedKey.encryptedConversationKey());
                keyBackupRepository.save(backup);
            } else {
                storeSessionConversationKey(conversation.getId(), encryptedKey);
            }
        }
    }

    private void storeSessionConversationKey(UUID conversationId, EncryptedConversationKeyRequest encryptedKey) {
        UserSession session = sessionRepository.findById(encryptedKey.recipientKeyId())
                .filter(value -> value.getStatus() == SessionStatus.ACTIVE)
                .filter(value -> value.getExpiresAt().isAfter(Instant.now()))
                .filter(value -> value.getUser().getId().equals(encryptedKey.userId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Active session key not found"));
        try {
            RedisConversationKeyValue value = new RedisConversationKeyValue(
                    session.getId(),
                    encryptedKey.userId(),
                    conversationId,
                    encryptedKey.keyVersion(),
                    encryptedKey.encryptedConversationKey(),
                    session.getExpiresAt()
            );
            redisTemplate.opsForValue().set(
                    RedisKeyUtil.conversationKey(session.getId(), conversationId, encryptedKey.keyVersion()),
                    objectMapper.writeValueAsString(value),
                    Duration.between(Instant.now(), session.getExpiresAt())
            );
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot store session conversation key");
        }
    }

    private void validateEncryptedKeyRequest(
            Conversation conversation,
            ConversationKeyVersion keyVersion,
            EncryptedConversationKeyRequest encryptedKey
    ) {
        if (encryptedKey.keyVersion() != keyVersion.getKeyVersion()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Encrypted key version does not match conversation key version");
        }
        if (!isActiveMember(conversation, encryptedKey.userId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Encrypted key recipient must be an active conversation member");
        }
    }

    private MasterKey findMasterKeyForUser(EncryptedConversationKeyRequest encryptedKey) {
        MasterKey masterKey = masterKeyRepository.findById(encryptedKey.recipientKeyId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Master key not found"));
        if (!masterKey.getUser().getId().equals(encryptedKey.userId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Master key does not belong to encrypted key recipient");
        }
        return masterKey;
    }

    private boolean isActiveMember(Conversation conversation, UUID userId) {
        boolean presentInLoadedMembers = conversation.getMembers().stream()
                .anyMatch(member -> member.getUser().getId().equals(userId)
                        && member.getStatus() == ConversationMemberStatus.ACTIVE);
        if (presentInLoadedMembers) {
            return true;
        }
        return memberRepository.findByConversationIdAndUserIdAndStatus(
                conversation.getId(),
                userId,
                ConversationMemberStatus.ACTIVE
        ).isPresent();
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
