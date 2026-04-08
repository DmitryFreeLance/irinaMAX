package ru.dmitry.maxbot.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserPayload(
        Long user_id,
        String first_name,
        String last_name,
        String username,
        Boolean is_bot
) {
    public String displayName() {
        String full = ((first_name == null ? "" : first_name) + " " + (last_name == null ? "" : last_name)).trim();
        if (!full.isBlank()) {
            return full;
        }
        if (username != null && !username.isBlank()) {
            return "@" + username;
        }
        return "Пользователь " + user_id;
    }
}
