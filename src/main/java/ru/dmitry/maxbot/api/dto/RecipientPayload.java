package ru.dmitry.maxbot.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RecipientPayload(
        Long chat_id,
        String chat_type,
        Long user_id
) {
}
