package ru.dmitry.maxbot.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdatePayload(
        String update_type,
        Long timestamp,
        MessagePayload message,
        CallbackPayload callback,
        String user_locale,
        Long chat_id,
        UserPayload user,
        String payload
) {
}
