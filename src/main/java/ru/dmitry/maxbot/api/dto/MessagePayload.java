package ru.dmitry.maxbot.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessagePayload(
        UserPayload sender,
        RecipientPayload recipient,
        Long timestamp,
        MessageBodyPayload body
) {
}
