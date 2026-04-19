package ru.dmitry.maxbot.model;

public record OrderRecord(
        long id,
        long userId,
        String company,
        String fullName,
        String salad,
        String soup,
        String hot,
        String extra,
        long createdAt,
        String createdDateMsk
) {
}
