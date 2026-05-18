package com.messenger.mini_messenger.service.impl;

import com.messenger.mini_messenger.dto.request.SendMessageRequest;
import com.messenger.mini_messenger.dto.request.UpdateMessageRequest;
import com.messenger.mini_messenger.dto.response.ApiMessageResponse;
import com.messenger.mini_messenger.dto.response.MessageCreatedResponse;
import com.messenger.mini_messenger.dto.response.MessageResponse;
import com.messenger.mini_messenger.dto.websocket.RealtimeMessageRequest;
import com.messenger.mini_messenger.entity.Conversation;
import com.messenger.mini_messenger.entity.ConversationKeyVersion;
import com.messenger.mini_messenger.entity.Message;
import com.messenger.mini_messenger.entity.User;
import com.messenger.mini_messenger.enums.ConversationMemberStatus;
import com.messenger.mini_messenger.enums.MessageType;
import com.messenger.mini_messenger.exception.ApiException;
import com.messenger.mini_messenger.mapper.MessageMapper;
import com.messenger.mini_messenger.repository.ConversationKeyVersionRepository;
import com.messenger.mini_messenger.repository.ConversationMemberRepository;
import com.messenger.mini_messenger.repository.ConversationRepository;
import com.messenger.mini_messenger.repository.MessageRepository;
import com.messenger.mini_messenger.repository.UserRepository;
import com.messenger.mini_messenger.security.CurrentUser;
import com.messenger.mini_messenger.service.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MessageServiceImpl implements MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationKeyVersionRepository keyVersionRepository;
    private final UserRepository userRepository;
    private final MessageMapper messageMapper;

    public MessageServiceImpl(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            ConversationKeyVersionRepository keyVersionRepository,
            UserRepository userRepository,
            MessageMapper messageMapper
    ) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.keyVersionRepository = keyVersionRepository;
        this.userRepository = userRepository;
        this.messageMapper = messageMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getMessages(CurrentUser currentUser, UUID conversationId, int limit) {
        requireParticipant(currentUser, conversationId);
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Message limit must be between 1 and 100");
        }
        List<Message> messages = messageRepository.findByConversationIdAndDeletedAtIsNullOrderByCreatedAtDesc(conversationId);
        return messages.stream()
                .limit(limit)
                .sorted(Comparator.comparing(Message::getCreatedAt))
                .map(messageMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public MessageCreatedResponse sendMessage(CurrentUser currentUser, UUID conversationId, SendMessageRequest request) {
        requireParticipant(currentUser, conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
        User sender = userRepository.findById(currentUser.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        ConversationKeyVersion keyVersion = keyVersionRepository.findByConversationIdAndKeyVersion(conversationId, request.keyVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Conversation key version not found"));

        Message message = messageMapper.toEntity(request);
        message.setConversation(conversation);
        message.setSender(sender);
        message.setKeyVersion(keyVersion);
        messageRepository.save(message);

        conversation.setLastMessageAt(Instant.now());
        return new MessageCreatedResponse(message.getId(), conversationId, sender.getId(), message.getCreatedAt());
    }

    @Override
    @Transactional
    public MessageResponse sendRealtimeMessage(CurrentUser currentUser, RealtimeMessageRequest request) {
        if (!currentUser.userId().equals(request.senderId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "senderId must match authenticated user");
        }

        UUID conversationId = request.conversationId();
        requireParticipant(currentUser, conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));
        User sender = userRepository.findById(request.senderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        int keyVersionNumber = request.keyVersion() != null ? request.keyVersion() : conversation.getCurrentKeyVersion();
        ConversationKeyVersion keyVersion = keyVersionRepository.findByConversationIdAndKeyVersion(conversationId, keyVersionNumber)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Conversation key version not found"));

        SendMessageRequest sendRequest = new SendMessageRequest(
                request.clientMessageId(),
                request.cipherData(),
                request.iv(),
                request.aad(),
                keyVersionNumber,
                request.messageType() != null ? request.messageType() : MessageType.TEXT,
                request.clientCreatedAt() != null ? request.clientCreatedAt() : Instant.now(),
                request.attachments()
        );
        Message message = messageMapper.toEntity(sendRequest);
        message.setConversation(conversation);
        message.setSender(sender);
        message.setKeyVersion(keyVersion);
        messageRepository.save(message);

        conversation.setLastMessageAt(Instant.now());
        return messageMapper.toResponse(message);
    }

    @Override
    @Transactional
    public MessageResponse updateMessage(CurrentUser currentUser, UUID conversationId, UUID messageId, UpdateMessageRequest request) {
        requireParticipant(currentUser, conversationId);
        Message message = findMessageInConversation(conversationId, messageId);
        requireSender(currentUser, message);
        requireNotDeleted(message);
        ConversationKeyVersion keyVersion = keyVersionRepository.findByConversationIdAndKeyVersion(conversationId, request.keyVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Conversation key version not found"));

        messageMapper.applyEncryptedUpdate(message, request, keyVersion);
        message.setEditedAt(Instant.now());
        return messageMapper.toResponse(message);
    }

    @Override
    @Transactional
    public ApiMessageResponse deleteMessage(CurrentUser currentUser, UUID conversationId, UUID messageId) {
        requireParticipant(currentUser, conversationId);
        Message message = findMessageInConversation(conversationId, messageId);
        requireSender(currentUser, message);
        requireNotDeleted(message);
        message.setDeletedAt(Instant.now());
        return new ApiMessageResponse("Message deleted successfully");
    }

    private void requireParticipant(CurrentUser currentUser, UUID conversationId) {
        memberRepository.findByConversationIdAndUserIdAndStatus(
                        conversationId,
                        currentUser.userId(),
                        ConversationMemberStatus.ACTIVE
                )
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a participant of this conversation"));
    }

    private Message findMessageInConversation(UUID conversationId, UUID messageId) {
        return messageRepository.findById(messageId)
                .filter(message -> message.getConversation().getId().equals(conversationId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
    }

    private void requireSender(CurrentUser currentUser, Message message) {
        if (!message.getSender().getId().equals(currentUser.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only message sender can modify this message");
        }
    }

    private void requireNotDeleted(Message message) {
        if (message.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Message is already deleted");
        }
    }
}
