package ru.dmitry.maxbot.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CallbackPayload(
        Long timestamp,
        String callback_id,
        String payload,
        UserPayload user
) {
}
