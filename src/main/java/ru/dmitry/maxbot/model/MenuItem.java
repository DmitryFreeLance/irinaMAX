package ru.dmitry.maxbot.model;

public record MenuItem(
        long id,
        MenuCategory category,
        String name,
        int displayOrder,
        boolean active
) {
}
