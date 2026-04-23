package ru.dmitry.maxbot.db;

import ru.dmitry.maxbot.model.MenuCategory;
import ru.dmitry.maxbot.model.MenuItem;
import ru.dmitry.maxbot.model.OrderDraft;
import ru.dmitry.maxbot.model.OrderRecord;
import ru.dmitry.maxbot.model.PageResult;
import ru.dmitry.maxbot.model.SessionData;
import ru.dmitry.maxbot.model.SessionState;
import ru.dmitry.maxbot.model.UserProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Database {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final String jdbcUrl;

    public Database(Path sqlitePath, Set<Long> initialAdminIds) {
        try {
            Files.createDirectories(sqlitePath.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create SQLite directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + sqlitePath;
        init();
        seedInitialAdmins(initialAdminIds);
    }

    public void upsertUser(long userId, String firstName, String lastName, String username) {
        long now = Instant.now().toEpochMilli();
        String sql = """
                INSERT INTO users (user_id, first_name, last_name, username, is_admin, is_active, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, 0, 1, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    first_name = excluded.first_name,
                    last_name = excluded.last_name,
                    username = excluded.username,
                    is_active = 1,
                    last_seen_at = excluded.last_seen_at
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, firstName);
            statement.setString(3, lastName);
            statement.setString(4, username);
            statement.setLong(5, now);
            statement.setLong(6, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot upsert user", e);
        }
    }

    public boolean isAdmin(long userId) {
        String sql = "SELECT is_admin FROM users WHERE user_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt("is_admin") == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read admin flag", e);
        }
    }

    public void grantAdmin(long userId) {
        long now = Instant.now().toEpochMilli();
        String sql = """
                INSERT INTO users (user_id, first_name, last_name, username, is_admin, is_active, created_at, last_seen_at)
                VALUES (?, '', '', NULL, 1, 1, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET is_admin = 1, is_active = 1, last_seen_at = excluded.last_seen_at
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, now);
            statement.setLong(3, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot grant admin", e);
        }
    }

    public List<Long> adminIds() {
        List<Long> result = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id FROM users WHERE is_admin = 1 ORDER BY user_id")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("user_id"));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list admins", e);
        }
    }

    public SessionData session(long userId) {
        String sql = "SELECT * FROM sessions WHERE user_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new SessionData(
                            rs.getLong("user_id"),
                            SessionState.valueOf(rs.getString("state")),
                            rs.getString("salad"),
                            rs.getString("soup"),
                            rs.getString("hot"),
                            rs.getString("extra"),
                            rs.getString("full_name"),
                            rs.getString("pending_category"),
                            rs.getLong("updated_at")
                    );
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load session", e);
        }
        SessionData empty = SessionData.empty(userId, Instant.now().toEpochMilli());
        saveSession(empty);
        return empty;
    }

    public void saveSession(SessionData session) {
        String sql = """
                INSERT INTO sessions (user_id, state, salad, soup, hot, extra, full_name, pending_category, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    state = excluded.state,
                    salad = excluded.salad,
                    soup = excluded.soup,
                    hot = excluded.hot,
                    extra = excluded.extra,
                    full_name = excluded.full_name,
                    pending_category = excluded.pending_category,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, session.userId());
            statement.setString(2, session.state().name());
            statement.setString(3, session.salad());
            statement.setString(4, session.soup());
            statement.setString(5, session.hot());
            statement.setString(6, session.extra());
            statement.setString(7, session.fullName());
            statement.setString(8, session.pendingCategory());
            statement.setLong(9, session.updatedAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save session", e);
        }
    }

    public void resetSession(long userId) {
        saveSession(SessionData.empty(userId, Instant.now().toEpochMilli()));
    }

    public List<MenuItem> menuItems(MenuCategory category) {
        List<MenuItem> items = new ArrayList<>();
        String sql = "SELECT id, category, name, display_order, active FROM menu_items WHERE category = ? AND active = 1 ORDER BY display_order, id";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, category.name());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    items.add(mapMenuItem(rs));
                }
            }
            return items;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load menu items", e);
        }
    }

    public Map<MenuCategory, List<MenuItem>> allMenuItemsByCategory() {
        Map<MenuCategory, List<MenuItem>> result = new EnumMap<>(MenuCategory.class);
        for (MenuCategory category : MenuCategory.ordered()) {
            result.put(category, menuItems(category));
        }
        return result;
    }

    public List<MenuItem> allMenuItemsFlat() {
        List<MenuItem> items = new ArrayList<>();
        String sql = "SELECT id, category, name, display_order, active FROM menu_items WHERE active = 1 ORDER BY CASE category WHEN 'SALAD' THEN 1 WHEN 'SOUP' THEN 2 WHEN 'HOT' THEN 3 WHEN 'EXTRA' THEN 4 ELSE 5 END, display_order, id";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    items.add(mapMenuItem(rs));
                }
            }
            return items;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load flat menu items", e);
        }
    }

    public void addMenuItem(MenuCategory category, String name) {
        String nextOrderSql = "SELECT COALESCE(MAX(display_order), 0) + 1 AS next_order FROM menu_items WHERE category = ? AND active = 1";
        String insertSql = "INSERT INTO menu_items (category, name, display_order, active, created_at) VALUES (?, ?, ?, 1, ?)";
        try (Connection connection = connection();
             PreparedStatement nextOrder = connection.prepareStatement(nextOrderSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            nextOrder.setString(1, category.name());
            int displayOrder = 1;
            try (ResultSet rs = nextOrder.executeQuery()) {
                if (rs.next()) {
                    displayOrder = rs.getInt("next_order");
                }
            }
            insert.setString(1, category.name());
            insert.setString(2, name);
            insert.setInt(3, displayOrder);
            insert.setLong(4, Instant.now().toEpochMilli());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot add menu item", e);
        }
    }

    public boolean deleteMenuItemBySequence(int sequenceNumber) {
        List<MenuItem> items = allMenuItemsFlat();
        if (sequenceNumber < 1 || sequenceNumber > items.size()) {
            return false;
        }
        long id = items.get(sequenceNumber - 1).id();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE menu_items SET active = 0 WHERE id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete menu item", e);
        }
    }

    public Map<String, List<OrderRecord>> allOrdersGroupedByDate() {
        Map<String, List<OrderRecord>> result = new LinkedHashMap<>();
        String sql = "SELECT id, user_id, full_name, salad, soup, hot, extra, created_at, created_date_msk FROM orders ORDER BY created_date_msk ASC, created_at ASC";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                OrderRecord record = new OrderRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("full_name"),
                        rs.getString("salad"),
                        rs.getString("soup"),
                        rs.getString("hot"),
                        rs.getString("extra"),
                        rs.getLong("created_at"),
                        rs.getString("created_date_msk")
                );
                result.computeIfAbsent(record.createdDateMsk(), key -> new ArrayList<>()).add(record);
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load orders for export", e);
        }
    }

    public void saveOrder(long userId, OrderDraft draft) {
        LocalDate today = LocalDate.now(MOSCOW);
        String sql = "INSERT INTO orders (user_id, full_name, salad, soup, hot, extra, created_at, created_date_msk) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        long now = Instant.now().toEpochMilli();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, draft.fullName());
            statement.setString(3, draft.salad());
            statement.setString(4, draft.soup());
            statement.setString(5, draft.hot());
            statement.setString(6, draft.extra());
            statement.setLong(7, now);
            statement.setString(8, today.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save order", e);
        }
    }

    public PageResult<UserProfile> activeUsers(int page) {
        return activeUsers(page, DEFAULT_PAGE_SIZE);
    }

    public PageResult<UserProfile> activeUsers(int page, int pageSize) {
        int totalItems = count("SELECT COUNT(*) FROM users WHERE is_active = 1");
        int totalPages = pageCount(totalItems, pageSize);
        int safePage = normalizePage(page, totalPages);
        List<UserProfile> users = new ArrayList<>();
        String sql = "SELECT user_id, first_name, last_name, username, is_admin, is_active, last_seen_at FROM users WHERE is_active = 1 ORDER BY last_seen_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, pageSize);
            statement.setInt(2, safePage * pageSize);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    users.add(new UserProfile(
                            rs.getLong("user_id"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            rs.getString("username"),
                            rs.getInt("is_admin") == 1,
                            rs.getInt("is_active") == 1,
                            rs.getLong("last_seen_at")
                    ));
                }
            }
            return new PageResult<>(users, safePage, totalPages, totalItems, pageSize);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load active users", e);
        }
    }

    public PageResult<OrderRecord> todayOrders(int page) {
        return todayOrders(page, DEFAULT_PAGE_SIZE);
    }

    public PageResult<OrderRecord> todayOrders(int page, int pageSize) {
        LocalDate today = LocalDate.now(MOSCOW);
        int totalItems = countWithArg("SELECT COUNT(*) FROM orders WHERE created_date_msk = ?", today.toString());
        int totalPages = pageCount(totalItems, pageSize);
        int safePage = normalizePage(page, totalPages);
        List<OrderRecord> orders = new ArrayList<>();
        String sql = "SELECT id, user_id, full_name, salad, soup, hot, extra, created_at, created_date_msk FROM orders WHERE created_date_msk = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, today.toString());
            statement.setInt(2, pageSize);
            statement.setInt(3, safePage * pageSize);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    orders.add(new OrderRecord(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("full_name"),
                            rs.getString("salad"),
                            rs.getString("soup"),
                            rs.getString("hot"),
                            rs.getString("extra"),
                            rs.getLong("created_at"),
                            rs.getString("created_date_msk")
                    ));
                }
            }
            return new PageResult<>(orders, safePage, totalPages, totalItems, pageSize);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load today's orders", e);
        }
    }

    private void seedInitialAdmins(Set<Long> adminIds) {
        adminIds.forEach(this::grantAdmin);
    }

    private MenuItem mapMenuItem(ResultSet rs) throws SQLException {
        return new MenuItem(
                rs.getLong("id"),
                MenuCategory.fromString(rs.getString("category")),
                rs.getString("name"),
                rs.getInt("display_order"),
                rs.getInt("active") == 1
        );
    }

    private int count(String sql) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot execute count", e);
        }
    }

    private int countWithArg(String sql, String arg) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot execute count with arg", e);
        }
    }

    private int pageCount(int totalItems, int pageSize) {
        if (totalItems == 0) {
            return 0;
        }
        return (totalItems + pageSize - 1) / pageSize;
    }

    private int normalizePage(int page, int totalPages) {
        if (totalPages == 0) {
            return 0;
        }
        return Math.max(0, Math.min(page, totalPages - 1));
    }

    private void init() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        user_id INTEGER PRIMARY KEY,
                        first_name TEXT,
                        last_name TEXT,
                        username TEXT,
                        is_admin INTEGER NOT NULL DEFAULT 0,
                        is_active INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS menu_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        category TEXT NOT NULL,
                        name TEXT NOT NULL,
                        display_order INTEGER NOT NULL,
                        active INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        full_name TEXT NOT NULL,
                        salad TEXT,
                        soup TEXT,
                        hot TEXT,
                        extra TEXT,
                        created_at INTEGER NOT NULL,
                        created_date_msk TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        user_id INTEGER PRIMARY KEY,
                        state TEXT NOT NULL,
                        salad TEXT,
                        soup TEXT,
                        hot TEXT,
                        extra TEXT,
                        full_name TEXT,
                        pending_category TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialize database", e);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
