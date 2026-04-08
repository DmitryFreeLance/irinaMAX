package ru.dmitry.maxbot.model;

public enum SessionState {
    IDLE,
    ORDER_SALAD,
    ORDER_SOUP,
    ORDER_HOT,
    ORDER_EXTRA,
    ORDER_FULL_NAME,
    ORDER_CONFIRMATION,
    ADMIN_ADD_ADMIN_ID,
    ADMIN_ADD_MENU_NAME,
    ADMIN_DELETE_MENU_NUMBER;

    public static SessionState forOrderCategory(MenuCategory category) {
        return switch (category) {
            case SALAD -> ORDER_SALAD;
            case SOUP -> ORDER_SOUP;
            case HOT -> ORDER_HOT;
            case EXTRA -> ORDER_EXTRA;
        };
    }
}
