package ru.dmitry.maxbot.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ru.dmitry.maxbot.api.dto.UpdateListPayload;
import ru.dmitry.maxbot.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MaxBotApiClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String token;

    public MaxBotApiClient(AppConfig config) {
        this.baseUrl = config.apiBaseUrl();
        this.token = config.token();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public UpdateListPayload getUpdates(Long marker, int limit, int timeoutSeconds, List<String> types) {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/updates?limit=").append(limit)
                .append("&timeout=").append(timeoutSeconds);
        if (marker != null) {
            url.append("&marker=").append(marker);
        }
        if (types != null && !types.isEmpty()) {
            url.append("&types=").append(encode(String.join(",", types)));
        }

        HttpRequest request = baseRequest(url.toString())
                .GET()
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds + 20L, 45L)))
                .build();
        return send(request, UpdateListPayload.class);
    }

    public void sendTextMessage(long userId, String text, List<List<CallbackButton>> buttons) {
        MessageRequest body = new MessageRequest(text, buttons == null || buttons.isEmpty() ? null : List.of(new InlineKeyboardAttachment(buttons)));
        String url = baseUrl + "/messages?user_id=" + userId;
        postJson(url, body, JsonNode.class);
    }

    public void answerCallback(String callbackId) {
        String url = baseUrl + "/answers?callback_id=" + encode(callbackId);
        postJson(url, Map.of("notification", "Принято"), CallbackAnswerResponse.class);
    }

    public void answerCallbackWithNotification(String callbackId, String notification) {
        String url = baseUrl + "/answers?callback_id=" + encode(callbackId);
        postJson(url, Map.of("notification", notification), CallbackAnswerResponse.class);
    }

    private <T> T postJson(String url, Object body, Class<T> responseType) {
        HttpRequest request = baseRequest(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
        return send(request, responseType);
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", token)
                .header("Accept", "application/json");
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MAX API error " + response.statusCode() + ": " + response.body());
            }
            if (responseType == Void.class) {
                return null;
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read MAX API response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MAX API request interrupted", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize JSON", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record MessageRequest(String text, List<InlineKeyboardAttachment> attachments) {
    }

    public record InlineKeyboardAttachment(String type, Payload payload) {
        public InlineKeyboardAttachment(List<List<CallbackButton>> buttons) {
            this("inline_keyboard", new Payload(buttons));
        }
    }

    public record Payload(List<List<CallbackButton>> buttons) {
    }

    public record CallbackButton(String type, String text, String payload) {
        public CallbackButton(String text, String payload) {
            this("callback", text, payload);
        }
    }

    public record CallbackAnswerResponse(boolean success, String message) {
    }

    public static List<List<CallbackButton>> keyboardRows() {
        return new ArrayList<>();
    }

    public static List<CallbackButton> row(CallbackButton... buttons) {
        return List.of(buttons);
    }

    public static CallbackButton button(String text, String payload) {
        return new CallbackButton(text, payload);
    }

    public static List<List<CallbackButton>> ofRows(List<CallbackButton>... rows) {
        return Arrays.stream(rows)
                .filter(Objects::nonNull)
                .toList();
    }
}
