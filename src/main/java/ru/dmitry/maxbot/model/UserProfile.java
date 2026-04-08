package ru.dmitry.maxbot.model;

public record UserProfile(
        long userId,
        String firstName,
        String lastName,
        String username,
        boolean admin,
        boolean active,
        long lastSeenAt
) {
    public String displayName() {
        String fullName = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (username != null && !username.isBlank()) {
            return "@" + username;
        }
        return "Пользователь " + userId;
    }
}
