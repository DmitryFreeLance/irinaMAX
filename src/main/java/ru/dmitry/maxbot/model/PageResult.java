package ru.dmitry.maxbot.model;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        int page,
        int totalPages,
        int totalItems,
        int pageSize
) {
    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < totalPages;
    }

    public int displayPage() {
        return totalPages == 0 ? 1 : page + 1;
    }
}
