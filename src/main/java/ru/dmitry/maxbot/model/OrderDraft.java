package ru.dmitry.maxbot.model;

public record OrderDraft(
        String company,
        String salad,
        String soup,
        String hot,
        String extra,
        String fullName
) {
    public OrderDraft withCompany(String newCompany) {
        return new OrderDraft(newCompany, salad, soup, hot, extra, fullName);
    }

    public OrderDraft withCategory(MenuCategory category, String value) {
        return switch (category) {
            case SALAD -> new OrderDraft(company, value, soup, hot, extra, fullName);
            case SOUP -> new OrderDraft(company, salad, value, hot, extra, fullName);
            case HOT -> new OrderDraft(company, salad, soup, value, extra, fullName);
            case EXTRA -> new OrderDraft(company, salad, soup, hot, value, fullName);
        };
    }

    public OrderDraft withFullName(String newFullName) {
        return new OrderDraft(company, salad, soup, hot, extra, newFullName);
    }

    public OrderDraft resetOrderChoices() {
        return new OrderDraft(company, null, null, null, null, null);
    }

    public static OrderDraft empty() {
        return new OrderDraft(null, null, null, null, null, null);
    }
}
