package ru.dmitry.maxbot.model;

public record CompanyItem(
        long id,
        String name,
        int displayOrder,
        boolean active
) {
}
