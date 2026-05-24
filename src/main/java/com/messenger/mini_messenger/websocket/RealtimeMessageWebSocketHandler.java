package com.messenger.mini_messenger.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messenger.mini_messenger.dto.response.MessageResponse;
import com.messenger.mini_messenger.dto.websocket.RealtimeErrorEvent;
import com.messenger.mini_messenger.dto.websocket.RealtimeMessageEvent;
import com.messenger.mini_messenger.dto.websocket.RealtimeMessageRequest;
import com.messenger.mini_messenger.enums.ConversationMemberStatus;
import com.messenger.mini_messenger.exception.ApiException;
import com.messenger.mini_messenger.repository.ConversationMemberRepository;
import com.messenger.mini_messenger.security.CurrentUser;
import com.messenger.mini_messenger.service.MessageService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import com.messenger.mini_messenger.entity.ConversationMember;
import com.messenger.mini_messenger.entity.UserSession;
import com.messenger.mini_messenger.dto.event.MemberLeftEvent;
import com.messenger.mini_messenger.repository.UserSessionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeMessageWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final MessageService messageService;
    private final ConversationMemberRepository memberRepository;
    private final UserSessionRepository userSessionRepository;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByUserId = new ConcurrentHashMap<>();

    public RealtimeMessageWebSocketHandler(
            ObjectMapper objectMapper,
            Validator validator,
            MessageService messageService,
            ConversationMemberRepository memberRepository,
            UserSessionRepository userSessionRepository
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.messageService = messageService;
        this.memberRepository = memberRepository;
        this.userSessionRepository = userSessionRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        CurrentUser currentUser = currentUser(session);
        sessionsByUserId.computeIfAbsent(currentUser.userId(), ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        try {
            String payload = textMessage.getPayload();
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(payload);
            if (jsonNode.has("type") && "key.request".equals(jsonNode.get("type").asText())) {
                handleKeyRequest(session, jsonNode);
            } else {
                RealtimeMessageRequest request = objectMapper.treeToValue(jsonNode, RealtimeMessageRequest.class);
                validate(request);
                MessageResponse savedMessage = messageService.sendRealtimeMessage(currentUser(session), request);
                broadcastToParticipants(savedMessage);
            }
        } catch (ApiException exception) {
            sendError(session, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(session, exception.getMessage());
        } catch (Exception exception) {
            sendError(session, "Invalid realtime message payload");
        }
    }

    private void handleKeyRequest(WebSocketSession session, com.fasterxml.jackson.databind.JsonNode jsonNode) throws IOException {
        CurrentUser currentUser = currentUser(session);
        UUID conversationId = UUID.fromString(jsonNode.get("conversationId").asText());
        int keyVersion = jsonNode.has("keyVersion") ? jsonNode.get("keyVersion").asInt() : 1;

        ConversationMember owner = memberRepository.findByConversationIdAndUserIdAndStatus(conversationId, currentUser.userId(), ConversationMemberStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a participant of this conversation"))
                .getConversation()
                .getMembers()
                .stream()
                .filter(m -> m.getRole() == com.messenger.mini_messenger.enums.ConversationMemberRole.OWNER && m.getStatus() == ConversationMemberStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation owner not found"));

        UserSession userSession = userSessionRepository.findById(currentUser.sessionKeyId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Session not found"));

        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("conversationId", conversationId);
        data.put("requesterId", currentUser.userId());
        data.put("requesterSessionKeyId", currentUser.sessionKeyId());
        data.put("requesterSessionPublicKey", userSession.getSessionPublicKey());
        data.put("keyVersion", keyVersion);

        java.util.Map<String, Object> event = new java.util.HashMap<>();
        event.put("type", "key.request");
        event.put("data", data);

        String eventPayload = objectMapper.writeValueAsString(event);
        sendToUser(owner.getUser().getId(), eventPayload);
    }

    @EventListener
    public void handleMemberLeft(MemberLeftEvent event) {
        try {
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("conversationId", event.conversationId());
            data.put("leftUserId", event.userId());

            java.util.Map<String, Object> payloadMap = new java.util.HashMap<>();
            payloadMap.put("type", "member.left");
            payloadMap.put("data", data);

            String payload = objectMapper.writeValueAsString(payloadMap);
            memberRepository.findUserIdsByConversationIdAndStatus(event.conversationId(), ConversationMemberStatus.ACTIVE)
                    .forEach(userId -> sendToUser(userId, payload));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Object attribute = session.getAttributes().get(JwtHandshakeInterceptor.CURRENT_USER_ATTRIBUTE);
        if (attribute instanceof CurrentUser currentUser) {
            Set<WebSocketSession> sessions = sessionsByUserId.get(currentUser.userId());
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    sessionsByUserId.remove(currentUser.userId());
                }
            }
        }
    }

    private void validate(RealtimeMessageRequest request) {
        Set<ConstraintViolation<RealtimeMessageRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.iterator().next().getPropertyPath() + " " + violations.iterator().next().getMessage());
        }
    }

    private void broadcastToParticipants(MessageResponse message) throws IOException {
        String payload = objectMapper.writeValueAsString(RealtimeMessageEvent.created(message));
        memberRepository.findUserIdsByConversationIdAndStatus(message.conversationId(), ConversationMemberStatus.ACTIVE)
                .forEach(userId -> sendToUser(userId, payload));
    }

    private void sendToUser(UUID userId, String payload) {
        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        if (sessions == null) {
            return;
        }

        sessions.removeIf(session -> !session.isOpen());
        sessions.forEach(session -> send(session, payload));
    }

    private void send(WebSocketSession session, String payload) {
        try {
            session.sendMessage(new TextMessage(payload));
        } catch (IOException exception) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
            }
        }
    }

    private void sendError(WebSocketSession session, String message) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(RealtimeErrorEvent.error(message))));
        }
    }

    private CurrentUser currentUser(WebSocketSession session) {
        return (CurrentUser) session.getAttributes().get(JwtHandshakeInterceptor.CURRENT_USER_ATTRIBUTE);
    }
}
