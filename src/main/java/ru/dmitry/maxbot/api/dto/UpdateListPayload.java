package ru.dmitry.maxbot.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateListPayload(
        List<UpdatePayload> updates,
        Long marker
) {
}
