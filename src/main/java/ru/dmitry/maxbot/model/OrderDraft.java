package ru.dmitry.maxbot.model;

public record OrderDraft(
        String salad,
        String soup,
        String hot,
        String extra,
        String fullName
) {
    public OrderDraft withCategory(MenuCategory category, String value) {
        return switch (category) {
            case SALAD -> new OrderDraft(value, soup, hot, extra, fullName);
            case SOUP -> new OrderDraft(salad, value, hot, extra, fullName);
            case HOT -> new OrderDraft(salad, soup, value, extra, fullName);
            case EXTRA -> new OrderDraft(salad, soup, hot, value, fullName);
        };
    }

    public OrderDraft withFullName(String newFullName) {
        return new OrderDraft(salad, soup, hot, extra, newFullName);
    }

    public static OrderDraft empty() {
        return new OrderDraft(null, null, null, null, null);
    }
}
