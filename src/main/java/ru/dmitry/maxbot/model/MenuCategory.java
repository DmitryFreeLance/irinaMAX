package ru.dmitry.maxbot.model;

import java.util.Arrays;
import java.util.List;

public enum MenuCategory {
    SALAD("Салат", "Выберите салат:"),
    SOUP("Суп", "Выберите суп:"),
    HOT("Горячее", "Выберите горячее:"),
    EXTRA("Доп. позиция", "Выберите доп позицию:");

    private static final List<MenuCategory> ORDER = List.of(SALAD, SOUP, HOT, EXTRA);

    private final String title;
    private final String question;

    MenuCategory(String title, String question) {
        this.title = title;
        this.question = question;
    }

    public String title() {
        return title;
    }

    public String question() {
        return question;
    }

    public MenuCategory next() {
        int index = ORDER.indexOf(this);
        return index >= 0 && index + 1 < ORDER.size() ? ORDER.get(index + 1) : null;
    }

    public static List<MenuCategory> ordered() {
        return ORDER;
    }

    public static MenuCategory fromString(String raw) {
        return Arrays.stream(values())
                .filter(value -> value.name().equalsIgnoreCase(raw))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown category: " + raw));
    }
}
