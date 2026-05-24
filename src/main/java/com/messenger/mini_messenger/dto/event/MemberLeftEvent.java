package com.messenger.mini_messenger.dto.event;

import java.util.UUID;

public record MemberLeftEvent(
        UUID conversationId,
        UUID userId
) {
}
