package ru.dmitry.maxbot.model;

public record SessionData(
        long userId,
        SessionState state,
        String company,
        String salad,
        String soup,
        String hot,
        String extra,
        String fullName,
        String pendingCategory,
        long updatedAt
) {
    public static SessionData empty(long userId, long now) {
        return new SessionData(userId, SessionState.IDLE, null, null, null, null, null, null, null, now);
    }

    public OrderDraft draft() {
        return new OrderDraft(company, salad, soup, hot, extra, fullName);
    }

    public SessionData withState(SessionState newState, long now) {
        return new SessionData(userId, newState, company, salad, soup, hot, extra, fullName, pendingCategory, now);
    }

    public SessionData withDraft(OrderDraft draft, long now) {
        return new SessionData(userId, state, draft.company(), draft.salad(), draft.soup(), draft.hot(), draft.extra(), draft.fullName(), pendingCategory, now);
    }

    public SessionData withPendingCategory(MenuCategory category, long now) {
        return new SessionData(userId, state, company, salad, soup, hot, extra, fullName, category == null ? null : category.name(), now);
    }

    public MenuCategory pendingCategoryEnum() {
        return pendingCategory == null ? null : MenuCategory.fromString(pendingCategory);
    }
}
