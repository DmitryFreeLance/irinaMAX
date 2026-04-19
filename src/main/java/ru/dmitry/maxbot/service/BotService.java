package ru.dmitry.maxbot.service;

import ru.dmitry.maxbot.api.MaxBotApiClient;
import ru.dmitry.maxbot.api.dto.CallbackPayload;
import ru.dmitry.maxbot.api.dto.MessagePayload;
import ru.dmitry.maxbot.api.dto.UpdateListPayload;
import ru.dmitry.maxbot.api.dto.UpdatePayload;
import ru.dmitry.maxbot.api.dto.UserPayload;
import ru.dmitry.maxbot.config.AppConfig;
import ru.dmitry.maxbot.db.Database;
import ru.dmitry.maxbot.model.CompanyItem;
import ru.dmitry.maxbot.model.MenuCategory;
import ru.dmitry.maxbot.model.MenuItem;
import ru.dmitry.maxbot.model.OrderDraft;
import ru.dmitry.maxbot.model.OrderRecord;
import ru.dmitry.maxbot.model.PageResult;
import ru.dmitry.maxbot.model.SessionData;
import ru.dmitry.maxbot.model.SessionState;
import ru.dmitry.maxbot.model.UserProfile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static ru.dmitry.maxbot.api.MaxBotApiClient.button;
import static ru.dmitry.maxbot.api.MaxBotApiClient.ofRows;
import static ru.dmitry.maxbot.api.MaxBotApiClient.row;

public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter MOSCOW_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(MOSCOW);
    private static final DateTimeFormatter MOSCOW_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(MOSCOW);
    private static final String SKIPPED = "Пропущено";
    private static final int MAX_INTERNAL_ERROR_STREAK = 3;
    private static final int NETWORK_ERROR_STREAK_BEFORE_MARKER_RESET = 20;
    private static final List<String> UPDATE_TYPES = List.of("message_created", "message_callback", "bot_started");

    private final AppConfig config;
    private final Database database;
    private final MaxBotApiClient apiClient;
    private Long marker;
    private int internalErrorStreak;
    private int transientNetworkErrorStreak;

    public BotService(AppConfig config, Database database, MaxBotApiClient apiClient) {
        this.config = config;
        this.database = database;
        this.apiClient = apiClient;
    }

    public void run() {
        log.info("MAX bot started. DB={}, baseUrl={}", config.sqlitePath(), config.apiBaseUrl());
        while (true) {
            try {
                UpdateListPayload updates = apiClient.getUpdates(marker, config.pollLimit(), config.pollTimeoutSeconds(), UPDATE_TYPES);
                Long nextMarker = updates.marker();
                if (updates.updates() != null) {
                    for (UpdatePayload update : updates.updates()) {
                        try {
                            handleUpdate(update);
                        } catch (Exception e) {
                            log.error("Update processing failed for type {}", update == null ? null : update.update_type(), e);
                        }
                    }
                }
                marker = nextMarker;
                internalErrorStreak = 0;
                transientNetworkErrorStreak = 0;
            } catch (Exception e) {
                if (isHttpTimeout(e)) {
                    log.warn("Long polling request timed out, retrying");
                    continue;
                }
                if (isMaxInternalError(e)) {
                    internalErrorStreak++;
                    log.warn("MAX API internal error on /updates (streak={}), retrying", internalErrorStreak);
                    if (internalErrorStreak >= MAX_INTERNAL_ERROR_STREAK) {
                        marker = null;
                        internalErrorStreak = 0;
                        log.warn("Reset polling marker after repeated MAX internal errors");
                    }
                    sleepQuietly(1);
                    continue;
                }
                if (isTransientNetworkError(e)) {
                    transientNetworkErrorStreak++;
                    if (transientNetworkErrorStreak == 1 || transientNetworkErrorStreak % 10 == 0) {
                        log.warn("Transient network error while polling (streak={}): {}", transientNetworkErrorStreak, rootCauseMessage(e));
                    }
                    if (transientNetworkErrorStreak >= NETWORK_ERROR_STREAK_BEFORE_MARKER_RESET) {
                        marker = null;
                        transientNetworkErrorStreak = 0;
                        log.warn("Reset polling marker after repeated transient network errors");
                    }
                    sleepQuietly(1);
                    continue;
                }
                transientNetworkErrorStreak = 0;
                log.error("Polling failed", e);
                sleepQuietly(3);
            }
        }
    }

    private boolean isHttpTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isMaxInternalError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("MAX API error 500")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTransientNetworkError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketException || current instanceof EOFException || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootCauseMessage(Throwable error) {
        Throwable current = error;
        Throwable last = error;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        return last == null ? "unknown" : String.valueOf(last.getMessage());
    }

    private void handleUpdate(UpdatePayload update) {
        if (update == null || update.update_type() == null) {
            return;
        }
        switch (update.update_type()) {
            case "message_created" -> handleMessageCreated(update.message());
            case "message_callback" -> handleCallback(update.callback());
            case "bot_started" -> handleBotStarted(update);
            default -> log.debug("Ignored update type {}", update.update_type());
        }
    }

    private void handleBotStarted(UpdatePayload update) {
        UserPayload user = update.user();
        if (user == null || user.user_id() == null) {
            return;
        }
        long userId = user.user_id();
        database.upsertUser(userId, user.first_name(), user.last_name(), user.username());
        database.resetSession(userId);
        sendCompanySelection(userId);
    }

    private void handleMessageCreated(MessagePayload message) {
        if (message == null || message.sender() == null || message.sender().user_id() == null) {
            return;
        }
        UserPayload sender = message.sender();
        long userId = sender.user_id();
        database.upsertUser(userId, sender.first_name(), sender.last_name(), sender.username());
        SessionData session = database.session(userId);
        String text = message.body() == null ? null : message.body().text();
        if (text == null || text.isBlank()) {
            sendText(userId, "Отправьте текстовое сообщение или воспользуйтесь кнопками ниже.", startKeyboard(userId));
            return;
        }
        text = text.trim();

        if ("/start".equalsIgnoreCase(text) || "начать".equalsIgnoreCase(text)) {
            database.resetSession(userId);
            sendCompanySelection(userId);
            return;
        }

        switch (session.state()) {
            case ORDER_FULL_NAME -> handleFullNameInput(userId, text, session);
            case ADMIN_ADD_ADMIN_ID -> handleAddAdminInput(userId, text);
            case ADMIN_ADD_MENU_NAME -> handleMenuNameInput(userId, text, session);
            case ADMIN_DELETE_MENU_NUMBER -> handleDeleteMenuNumberInput(userId, text);
            case ADMIN_ADD_COMPANY_NAME -> handleAddCompanyInput(userId, text);
            case ADMIN_DELETE_COMPANY_NUMBER -> handleDeleteCompanyNumberInput(userId, text);
            default -> handleIdleText(userId, text);
        }
    }

    private void handleCallback(CallbackPayload callback) {
        if (callback == null || callback.user() == null || callback.user().user_id() == null || callback.payload() == null) {
            return;
        }
        UserPayload user = callback.user();
        long userId = user.user_id();
        database.upsertUser(userId, user.first_name(), user.last_name(), user.username());
        try {
            apiClient.answerCallback(callback.callback_id());
        } catch (Exception e) {
            log.warn("Failed to answer callback {}", callback.callback_id(), e);
        }

        String payload = callback.payload();
        if (payload.equals("nav:start")) {
            database.resetSession(userId);
            sendCompanySelection(userId);
            return;
        }
        if (payload.startsWith("order:company:")) {
            chooseCompany(userId, payload.substring("order:company:".length()));
            return;
        }
        if (payload.equals("nav:order")) {
            startOrder(userId);
            return;
        }
        if (payload.equals("nav:admin")) {
            if (ensureAdmin(userId)) {
                showAdminPanel(userId);
            }
            return;
        }
        if (payload.equals("nav:survey")) {
            database.resetSession(userId);
            sendCompanySelection(userId);
            return;
        }
        if (payload.equals("order:confirm")) {
            confirmOrder(userId);
            return;
        }
        if (payload.equals("order:cancel")) {
            database.resetSession(userId);
            sendCompanySelection(userId);
            return;
        }
        if (payload.startsWith("order:pick:")) {
            handleOrderChoice(userId, payload, false);
            return;
        }
        if (payload.startsWith("order:skip:")) {
            handleOrderChoice(userId, payload, true);
            return;
        }

        if (!ensureAdmin(userId)) {
            return;
        }

        if (payload.equals("admin:panel")) {
            showAdminPanel(userId);
            return;
        }
        if (payload.startsWith("admin:users:")) {
            showUsers(userId, parsePage(payload));
            return;
        }
        if (payload.startsWith("admin:orders:")) {
            showOrders(userId, parsePage(payload));
            return;
        }
        if (payload.equals("admin:addAdmin")) {
            database.saveSession(database.session(userId).withState(SessionState.ADMIN_ADD_ADMIN_ID, now()));
            sendText(userId, "Введите ID пользователя, которого нужно сделать администратором.", adminBackKeyboard());
            return;
        }
        if (payload.equals("admin:menu")) {
            showMenuManagement(userId);
            return;
        }
        if (payload.equals("admin:menu:view")) {
            showCurrentMenu(userId);
            return;
        }
        if (payload.equals("admin:menu:add")) {
            sendText(userId, "Выберите раздел для новой позиции:", menuCategoryKeyboard("admin:menu:addCategory:"));
            return;
        }
        if (payload.startsWith("admin:menu:addCategory:")) {
            MenuCategory category = MenuCategory.fromString(payload.substring("admin:menu:addCategory:".length()));
            SessionData updated = database.session(userId)
                    .withState(SessionState.ADMIN_ADD_MENU_NAME, now())
                    .withPendingCategory(category, now());
            database.saveSession(updated);
            sendText(userId, "Введите название для раздела \"" + category.title() + "\".", adminBackKeyboard());
            return;
        }
        if (payload.equals("admin:menu:delete")) {
            database.saveSession(database.session(userId).withState(SessionState.ADMIN_DELETE_MENU_NUMBER, now()));
            sendText(userId, currentMenuText() + "\n\nВведите порядковый номер позиции, которую нужно удалить.", adminBackWithAddMoreKeyboard());
            return;
        }
        if (payload.equals("admin:companies")) {
            showCompanyManagement(userId);
            return;
        }
        if (payload.equals("admin:companies:view")) {
            showCurrentCompanies(userId);
            return;
        }
        if (payload.equals("admin:companies:add")) {
            database.saveSession(database.session(userId).withState(SessionState.ADMIN_ADD_COMPANY_NAME, now()));
            sendText(userId, "Введите название компании.", adminBackKeyboard());
            return;
        }
        if (payload.equals("admin:companies:delete")) {
            database.saveSession(database.session(userId).withState(SessionState.ADMIN_DELETE_COMPANY_NUMBER, now()));
            sendText(userId, currentCompaniesText() + "\n\nВведите порядковый номер компании для удаления.", adminBackKeyboard());
            return;
        }
        if (payload.equals("admin:excel") || payload.equals("admin:excel:refresh")) {
            sendExcelExport(userId);
        }
    }

    private void handleIdleText(long userId, String text) {
        if (equalsAny(text, "сделать заказ", "заказать", "заказ")) {
            startOrder(userId);
            return;
        }
        if (equalsAny(text, "админ панель", "админка") && database.isAdmin(userId)) {
            showAdminPanel(userId);
            return;
        }
        sendCompanySelection(userId);
    }

    private void chooseCompany(long userId, String encodedCompany) {
        String company = unescapeMenuValue(encodedCompany);
        boolean exists = database.companies().stream().anyMatch(c -> c.name().equals(company));
        if (!exists) {
            sendCompanySelection(userId);
            return;
        }
        SessionData session = database.session(userId);
        SessionData updated = session.withDraft(session.draft().withCompany(company).resetOrderChoices(), now())
                .withState(SessionState.IDLE, now())
                .withPendingCategory(null, now());
        database.saveSession(updated);
        sendStartScreen(userId);
    }

    private void sendCompanySelection(long userId) {
        List<CompanyItem> companies = database.companies();
        if (companies.isEmpty()) {
            String text = "Список компаний пока пуст. Обратитесь к администратору для добавления компаний.";
            if (database.isAdmin(userId)) {
                sendText(userId, text, ofRows(
                        row(button("🏢 Управление компаниями", "admin:companies")),
                        row(button("🛠️ Админ панель", "admin:panel"))
                ));
            } else {
                sendText(userId, text, List.of());
            }
            return;
        }

        List<List<MaxBotApiClient.CallbackButton>> rows = new ArrayList<>();
        for (CompanyItem company : companies) {
            rows.add(row(button(company.name(), "order:company:" + escapeMenuValue(company.name()))));
        }
        if (database.isAdmin(userId)) {
            rows.add(row(button("🛠️ Админ панель", "nav:admin")));
        }
        sendText(userId, "Выберите компанию:", rows);
    }

    private void startOrder(long userId) {
        SessionData session = database.session(userId);
        if (session.draft().company() == null || session.draft().company().isBlank()) {
            sendCompanySelection(userId);
            return;
        }
        session = session.withState(SessionState.ORDER_SALAD, now())
                .withDraft(session.draft().resetOrderChoices(), now())
                .withPendingCategory(null, now());
        database.saveSession(session);
        askCategory(userId, MenuCategory.SALAD);
    }

    private void askCategory(long userId, MenuCategory category) {
        List<MenuItem> items = database.menuItems(category);
        List<List<MaxBotApiClient.CallbackButton>> rows = new ArrayList<>();
        for (MenuItem item : items) {
            rows.add(row(button(item.name(), "order:pick:" + category.name() + ":" + escapeMenuValue(item.name()))));
        }
        rows.add(row(button("⏭️ Пропустить", "order:skip:" + category.name())));
        sendText(userId, category.question(), rows);
    }

    private void handleOrderChoice(long userId, String payload, boolean skipped) {
        SessionData session = database.session(userId);
        String prefix = skipped ? "order:skip:" : "order:pick:";
        String raw = payload.substring(prefix.length());
        String[] parts = raw.split(":", 2);
        MenuCategory category = MenuCategory.fromString(parts[0]);
        SessionState expectedState = SessionState.forOrderCategory(category);
        if (session.state() != expectedState) {
            sendText(userId, "Сценарий устарел. Давайте начнем заказ заново.", startKeyboard(userId));
            database.resetSession(userId);
            return;
        }

        String value = skipped ? SKIPPED : unescapeMenuValue(parts.length > 1 ? parts[1] : "");
        OrderDraft draft = session.draft().withCategory(category, value);
        MenuCategory nextCategory = category.next();
        if (nextCategory == null) {
            SessionData updated = session.withDraft(draft, now()).withState(SessionState.ORDER_FULL_NAME, now());
            database.saveSession(updated);
            sendText(userId, "Введите ваше ФИО.", ofRows(row(button("❌ Отменить", "order:cancel"))));
        } else {
            SessionData updated = session.withDraft(draft, now()).withState(SessionState.forOrderCategory(nextCategory), now());
            database.saveSession(updated);
            askCategory(userId, nextCategory);
        }
    }

    private void handleFullNameInput(long userId, String text, SessionData session) {
        String fullName = text.trim();
        if (fullName.length() < 5) {
            sendText(userId, "Пожалуйста, введите ФИО полностью.", ofRows(row(button("❌ Отменить", "order:cancel"))));
            return;
        }
        OrderDraft draft = session.draft().withFullName(fullName);
        SessionData updated = session.withDraft(draft, now()).withState(SessionState.ORDER_CONFIRMATION, now());
        database.saveSession(updated);
        sendText(userId, orderSummaryText(draft), ofRows(
                row(button("✅ Подтвердить", "order:confirm"), button("❌ Отменить", "order:cancel"))
        ));
    }

    private void confirmOrder(long userId) {
        SessionData session = database.session(userId);
        if (session.state() != SessionState.ORDER_CONFIRMATION) {
            sendText(userId, "Заказ не найден. Нажмите кнопку ниже, чтобы начать заново.", startKeyboard(userId));
            database.resetSession(userId);
            return;
        }
        OrderDraft draft = session.draft();
        database.saveOrder(userId, draft);
        notifyAdminsAboutOrder(userId, draft);
        database.resetSession(userId);
        sendText(userId,
                "Спасибо за заказ! Для оформления следующего заказа нажмите кнопку ниже.",
                ofRows(row(button("🍽️ Сделать заказ", "nav:order")), adminRowIfNeeded(userId))
        );
    }

    private void notifyAdminsAboutOrder(long userId, OrderDraft draft) {
        String text = "🆕 Новая заявка\n\n" +
                "Компания: " + safe(draft.company()) + "\n" +
                "ФИО: " + safe(draft.fullName()) + "\n" +
                "Пользователь ID: " + userId + "\n" +
                "Время: " + MOSCOW_TIME.format(Instant.now()) + " МСК\n\n" +
                composeOrderLines(draft);
        for (Long adminId : database.adminIds()) {
            try {
                apiClient.sendTextMessage(adminId, text, adminBackKeyboard());
            } catch (Exception e) {
                log.warn("Cannot notify admin {} about new order", adminId, e);
            }
        }
    }

    private void handleAddAdminInput(long userId, String text) {
        try {
            long newAdminId = Long.parseLong(text.trim());
            database.grantAdmin(newAdminId);
            database.resetSession(userId);
            sendText(userId, "✅ Администратор с ID " + newAdminId + " добавлен.", adminBackKeyboard());
        } catch (NumberFormatException e) {
            sendText(userId, "Не удалось распознать ID. Введите числовой ID пользователя.", adminBackKeyboard());
        }
    }

    private void handleMenuNameInput(long userId, String text, SessionData session) {
        MenuCategory category = session.pendingCategoryEnum();
        if (category == null) {
            database.resetSession(userId);
            showMenuManagement(userId);
            return;
        }
        database.addMenuItem(category, text.trim());
        database.resetSession(userId);
        sendText(userId, "✅ Позиция добавлена в раздел \"" + category.title() + "\".", adminBackWithAddMoreKeyboard());
    }

    private void handleDeleteMenuNumberInput(long userId, String text) {
        try {
            int number = Integer.parseInt(text.trim());
            boolean deleted = database.deleteMenuItemBySequence(number);
            database.resetSession(userId);
            if (deleted) {
                sendText(userId, "✅ Позиция удалена.", adminBackWithAddMoreKeyboard());
            } else {
                sendText(userId, "Позиция с таким номером не найдена.", adminBackWithAddMoreKeyboard());
            }
        } catch (NumberFormatException e) {
            sendText(userId, "Введите именно порядковый номер позиции.", adminBackWithAddMoreKeyboard());
        }
    }

    private void handleAddCompanyInput(long userId, String text) {
        String name = text.trim();
        if (name.isBlank()) {
            sendText(userId, "Название компании не может быть пустым.", adminBackKeyboard());
            return;
        }
        database.addCompany(name);
        database.resetSession(userId);
        sendText(userId, "✅ Компания добавлена.", companyBackWithAddMoreKeyboard());
    }

    private void handleDeleteCompanyNumberInput(long userId, String text) {
        try {
            int number = Integer.parseInt(text.trim());
            boolean deleted = database.deleteCompanyBySequence(number);
            database.resetSession(userId);
            if (deleted) {
                sendText(userId, "✅ Компания удалена.", companyBackWithAddMoreKeyboard());
            } else {
                sendText(userId, "Компания с таким номером не найдена.", companyBackWithAddMoreKeyboard());
            }
        } catch (NumberFormatException e) {
            sendText(userId, "Введите именно порядковый номер компании.", companyBackWithAddMoreKeyboard());
        }
    }

    private void showAdminPanel(long userId) {
        database.resetSession(userId);
        sendText(userId, "🛠️ Админ панель\nВыберите действие:", ofRows(
                row(button("👥 Все пользователи", "admin:users:0")),
                row(button("➕ Добавить админа", "admin:addAdmin")),
                row(button("📦 Заявки", "admin:orders:0")),
                row(button("🍽️ Изменить позиции", "admin:menu")),
                row(button("🏢 Компании", "admin:companies")),
                row(button("📊 Выгрузка excel", "admin:excel")),
                row(button("📝 Перейти к опросу", "nav:survey"))
        ));
    }

    private void showUsers(long userId, int page) {
        PageResult<UserProfile> result = database.activeUsers(page);
        StringBuilder text = new StringBuilder("👥 Активные пользователи\n");
        text.append("Страница: ").append(result.displayPage()).append("/").append(Math.max(result.totalPages(), 1)).append("\n");
        text.append("Всего: ").append(result.totalItems()).append("\n\n");
        if (result.items().isEmpty()) {
            text.append("Активных пользователей пока нет.");
        } else {
            int startIndex = result.page() * result.pageSize() + 1;
            for (int i = 0; i < result.items().size(); i++) {
                UserProfile user = result.items().get(i);
                text.append(startIndex + i).append(". ").append(user.displayName())
                        .append(" | ID: ").append(user.userId());
                if (user.username() != null && !user.username().isBlank()) {
                    text.append(" | @").append(user.username());
                }
                if (user.admin()) {
                    text.append(" | admin");
                }
                text.append("\n");
            }
        }
        sendText(userId, text.toString(), pagedAdminKeyboard("admin:users:", result, true));
    }

    private void showOrders(long userId, int page) {
        PageResult<OrderRecord> result = database.todayOrders(page);
        StringBuilder text = new StringBuilder("📦 Заявки за сегодня (МСК)\n");
        text.append("Дата: ").append(MOSCOW_DATE.format(Instant.now())).append("\n");
        text.append("Страница: ").append(result.displayPage()).append("/").append(Math.max(result.totalPages(), 1)).append("\n");
        text.append("Всего: ").append(result.totalItems()).append("\n\n");
        if (result.items().isEmpty()) {
            text.append("Сегодня заявок пока нет.");
        } else {
            for (OrderRecord order : result.items()) {
                text.append("#").append(order.id())
                        .append(" | ").append(MOSCOW_TIME.format(Instant.ofEpochMilli(order.createdAt()))).append(" МСК\n")
                        .append("Компания: ").append(safe(order.company())).append("\n")
                        .append("ФИО: ").append(safe(order.fullName())).append("\n")
                        .append("Салат: ").append(safe(order.salad())).append("\n")
                        .append("Суп: ").append(safe(order.soup())).append("\n")
                        .append("Горячее: ").append(safe(order.hot())).append("\n")
                        .append("Доп. позиция: ").append(safe(order.extra())).append("\n")
                        .append("Пользователь ID: ").append(order.userId()).append("\n\n");
            }
        }
        sendText(userId, text.toString().trim(), pagedAdminKeyboard("admin:orders:", result, true));
    }

    private void showMenuManagement(long userId) {
        sendText(userId, "🍽️ Управление позициями\nВыберите действие:", ofRows(
                row(button("📋 Текущие позиции", "admin:menu:view")),
                row(button("➕ Добавить позицию", "admin:menu:add")),
                row(button("➖ Удалить позицию", "admin:menu:delete")),
                row(button("↩️ В админ панель", "admin:panel"))
        ));
    }

    private void showCurrentMenu(long userId) {
        sendText(userId, currentMenuText(), ofRows(
                row(button("➕ Добавить позицию", "admin:menu:add")),
                row(button("➖ Удалить позицию", "admin:menu:delete")),
                row(button("↩️ В админ панель", "admin:panel"))
        ));
    }

    private String currentMenuText() {
        Map<MenuCategory, List<MenuItem>> items = database.allMenuItemsByCategory();
        StringBuilder text = new StringBuilder("📋 Текущие позиции\n\n");
        int index = 1;
        for (MenuCategory category : MenuCategory.ordered()) {
            text.append(categoryEmoji(category)).append(" ").append(category.title()).append(":\n");
            List<MenuItem> categoryItems = items.getOrDefault(category, List.of());
            if (categoryItems.isEmpty()) {
                text.append("- пока пусто\n\n");
                continue;
            }
            for (MenuItem item : categoryItems) {
                text.append(index++).append(". ").append(item.name()).append("\n");
            }
            text.append("\n");
        }
        return text.toString().trim();
    }

    private void showCompanyManagement(long userId) {
        sendText(userId, "🏢 Управление компаниями\nВыберите действие:", ofRows(
                row(button("📋 Текущие компании", "admin:companies:view")),
                row(button("➕ Добавить компанию", "admin:companies:add")),
                row(button("➖ Удалить компанию", "admin:companies:delete")),
                row(button("↩️ В админ панель", "admin:panel"))
        ));
    }

    private void showCurrentCompanies(long userId) {
        sendText(userId, currentCompaniesText(), ofRows(
                row(button("➕ Добавить компанию", "admin:companies:add")),
                row(button("➖ Удалить компанию", "admin:companies:delete")),
                row(button("↩️ В админ панель", "admin:panel"))
        ));
    }

    private String currentCompaniesText() {
        List<CompanyItem> companies = database.companies();
        StringBuilder text = new StringBuilder("🏢 Текущие компании\n\n");
        if (companies.isEmpty()) {
            text.append("- пока пусто");
            return text.toString();
        }
        for (int i = 0; i < companies.size(); i++) {
            text.append(i + 1).append(". ").append(companies.get(i).name()).append("\n");
        }
        return text.toString().trim();
    }

    private void sendExcelExport(long userId) {
        Path tempFile = null;
        try {
            tempFile = buildExcelExport();
            apiClient.sendFileMessage(userId, "📊 Выгрузка заказов", tempFile);
            sendText(userId, "Файл сформирован. Вы можете обновить его в любой момент.", excelActionsKeyboard());
        } catch (Exception e) {
            log.error("Failed to export excel", e);
            sendText(userId, "Не удалось сформировать Excel файл. Попробуйте позже.", excelActionsKeyboard());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Cannot delete temp excel {}", tempFile, e);
                }
            }
        }
    }

    private Path buildExcelExport() {
        Map<String, List<OrderRecord>> grouped = database.allOrdersGroupedByDate();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            if (grouped.isEmpty()) {
                Sheet sheet = workbook.createSheet("Пусто");
                Row row = sheet.createRow(0);
                row.createCell(0).setCellValue("Заявок пока нет");
            } else {
                Set<String> usedSheetNames = new HashSet<>();
                for (Map.Entry<String, List<OrderRecord>> entry : grouped.entrySet()) {
                    String sheetName = uniqueSheetName(usedSheetNames, entry.getKey());
                    Sheet sheet = workbook.createSheet(sheetName);
                    writeOrdersSheet(sheet, entry.getValue());
                }
            }

            Path temp = Files.createTempFile("orders-export-", ".xlsx");
            try (OutputStream output = Files.newOutputStream(temp)) {
                workbook.write(output);
            }
            return temp;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build excel file", e);
        }
    }

    private void writeOrdersSheet(Sheet sheet, List<OrderRecord> orders) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("ID");
        header.createCell(1).setCellValue("Компания");
        header.createCell(2).setCellValue("ФИО");
        header.createCell(3).setCellValue("Салат");
        header.createCell(4).setCellValue("Суп");
        header.createCell(5).setCellValue("Горячее");
        header.createCell(6).setCellValue("Доп. позиция");
        header.createCell(7).setCellValue("Пользователь ID");
        header.createCell(8).setCellValue("Время (МСК)");

        int rowNum = 1;
        for (OrderRecord order : orders) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(order.id());
            row.createCell(1).setCellValue(safe(order.company()));
            row.createCell(2).setCellValue(safe(order.fullName()));
            row.createCell(3).setCellValue(safe(order.salad()));
            row.createCell(4).setCellValue(safe(order.soup()));
            row.createCell(5).setCellValue(safe(order.hot()));
            row.createCell(6).setCellValue(safe(order.extra()));
            row.createCell(7).setCellValue(order.userId());
            row.createCell(8).setCellValue(MOSCOW_TIME.format(Instant.ofEpochMilli(order.createdAt())));
        }

        for (int i = 0; i <= 8; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private String uniqueSheetName(Set<String> used, String rawDate) {
        String base = sanitizeSheetName(rawDate == null || rawDate.isBlank() ? "Unknown" : rawDate);
        String name = base;
        int idx = 2;
        while (used.contains(name)) {
            String suffix = "_" + idx++;
            name = base.length() + suffix.length() > 31
                    ? base.substring(0, 31 - suffix.length()) + suffix
                    : base + suffix;
        }
        used.add(name);
        return name;
    }

    private String sanitizeSheetName(String name) {
        String cleaned = name.replaceAll("[\\\\/*?:\\[\\]]", "_");
        if (cleaned.isBlank()) {
            cleaned = "Sheet";
        }
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private void sendStartScreen(long userId) {
        SessionData session = database.session(userId);
        String selectedCompany = session.draft().company();
        String prefix = selectedCompany == null || selectedCompany.isBlank()
                ? "Компания не выбрана."
                : "Выбрана компания: " + selectedCompany + ".";
        sendText(userId, prefix + "\nЗдравствуйте! Для продолжения нажмите кнопку ниже. 👇", startKeyboard(userId));
    }

    private List<List<MaxBotApiClient.CallbackButton>> startKeyboard(long userId) {
        List<List<MaxBotApiClient.CallbackButton>> rows = new ArrayList<>();
        rows.add(row(button("🏢 Выбрать компанию", "nav:start")));
        rows.add(row(button("🍽️ Сделать заказ", "nav:order")));
        if (database.isAdmin(userId)) {
            rows.add(row(button("🛠️ Админ панель", "nav:admin")));
        }
        return rows;
    }

    private List<MaxBotApiClient.CallbackButton> adminRowIfNeeded(long userId) {
        if (!database.isAdmin(userId)) {
            return null;
        }
        return row(button("🛠️ Админ панель", "nav:admin"));
    }

    private List<List<MaxBotApiClient.CallbackButton>> adminBackKeyboard() {
        return ofRows(row(button("↩️ В админ панель", "admin:panel")));
    }

    private List<List<MaxBotApiClient.CallbackButton>> adminBackWithAddMoreKeyboard() {
        return ofRows(
                row(button("➕ Добавить еще", "admin:menu:add")),
                row(button("↩️ В админ панель", "admin:panel"))
        );
    }

    private List<List<MaxBotApiClient.CallbackButton>> companyBackWithAddMoreKeyboard() {
        return ofRows(
                row(button("➕ Добавить еще", "admin:companies:add")),
                row(button("↩️ В админ панель", "admin:panel"))
        );
    }

    private List<List<MaxBotApiClient.CallbackButton>> excelActionsKeyboard() {
        return ofRows(
                row(button("🔄 Обновить файл", "admin:excel:refresh")),
                row(button("↩️ В админ панель", "admin:panel"))
        );
    }

    private List<List<MaxBotApiClient.CallbackButton>> menuCategoryKeyboard(String prefix) {
        return ofRows(
                row(button("🥗 Салат", prefix + MenuCategory.SALAD.name())),
                row(button("🍲 Суп", prefix + MenuCategory.SOUP.name())),
                row(button("🍛 Горячее", prefix + MenuCategory.HOT.name())),
                row(button("🍰 Доп. позиция", prefix + MenuCategory.EXTRA.name())),
                row(button("↩️ В админ панель", "admin:panel"))
        );
    }

    private List<List<MaxBotApiClient.CallbackButton>> pagedAdminKeyboard(String prefix, PageResult<?> result, boolean includeBack) {
        List<List<MaxBotApiClient.CallbackButton>> rows = new ArrayList<>();
        List<MaxBotApiClient.CallbackButton> pagingRow = new ArrayList<>();
        if (result.hasPrevious()) {
            pagingRow.add(button("⬅️", prefix + (result.page() - 1)));
        }
        if (result.hasNext()) {
            pagingRow.add(button("➡️", prefix + (result.page() + 1)));
        }
        if (!pagingRow.isEmpty()) {
            rows.add(pagingRow);
        }
        if (includeBack) {
            rows.add(row(button("↩️ В админ панель", "admin:panel")));
        }
        return rows;
    }

    private void sendText(long userId, String text, List<List<MaxBotApiClient.CallbackButton>> rows) {
        List<List<MaxBotApiClient.CallbackButton>> sanitized = rows == null ? List.of() : rows.stream()
                .filter(Objects::nonNull)
                .toList();
        apiClient.sendTextMessage(userId, text, sanitized);
    }

    private boolean ensureAdmin(long userId) {
        if (!database.isAdmin(userId)) {
            sendText(userId, "Эта секция доступна только администраторам.", startKeyboard(userId));
            return false;
        }
        return true;
    }

    private String orderSummaryText(OrderDraft draft) {
        return "Ваш заказ:\n\nКомпания: " + safe(draft.company()) + "\n" + composeOrderLines(draft) + "\nФИО: " + safe(draft.fullName());
    }

    private String composeOrderLines(OrderDraft draft) {
        return "Салат: " + safe(draft.salad()) + "\n" +
                "Суп: " + safe(draft.soup()) + "\n" +
                "Горячее: " + safe(draft.hot()) + "\n" +
                "Доп. позиция: " + safe(draft.extra()) + "\n";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? SKIPPED : value;
    }

    private String categoryEmoji(MenuCategory category) {
        return switch (category) {
            case SALAD -> "🥗";
            case SOUP -> "🍲";
            case HOT -> "🍛";
            case EXTRA -> "🍰";
        };
    }

    private boolean equalsAny(String value, String... variants) {
        for (String variant : variants) {
            if (variant.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private int parsePage(String payload) {
        int idx = payload.lastIndexOf(':');
        if (idx < 0 || idx + 1 >= payload.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(payload.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String escapeMenuValue(String raw) {
        return raw.replace("%", "%25").replace(":", "%3A");
    }

    private String unescapeMenuValue(String raw) {
        return raw.replace("%3A", ":").replace("%25", "%");
    }

    private long now() {
        return Instant.now().toEpochMilli();
    }

    private void sleepQuietly(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
