package ru.mrcrubs.lmsnode.service;

import org.springframework.web.util.HtmlUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolve the selected source of Yandex Video, rather than downloading its search wrapper. */
public final class MediaSourceResolver {
    private static final class Network {
        static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }
    private static final Pattern PREVIEW = Pattern.compile("/video/preview/\\d+/?");
    private static final Pattern SOURCE = Pattern.compile("<a\\b(?=[^>]*\\bclass=[\"'][^\"']*\\bVideoViewer-Source(?:Path|Icon)Link\\b[^\"']*[\"'])[^>]*\\bhref=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private record Cached(String url, long until) {}
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();
    private MediaSourceResolver() {}

    public static String resolve(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null || !(host.equals("yandex.ru") || host.equals("www.yandex.ru")) || !PREVIEW.matcher(uri.getPath()).matches()) return url;
        synchronized (CACHE) {
            Cached hit = CACHE.get(url);
            if (hit != null && hit.until > System.currentTimeMillis()) return hit.url;
        }
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0").GET().build();
            var response = Network.CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String source;
            try (var body = response.body()) {
                if (response.statusCode() != 200) throw new MediaExtractException("Яндекс не отдал страницу превью. Откройте исходный видеосайт.");
                byte[] bytes = body.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new MediaExtractException("Слишком большая страница превью Яндекса");
                source = sourceFromHtml(new String(bytes, StandardCharsets.UTF_8));
            }
            synchronized (CACHE) {
                CACHE.entrySet().removeIf(e -> e.getValue().until < System.currentTimeMillis());
                if (CACHE.size() >= 64) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(url, new Cached(source, System.currentTimeMillis() + 600_000));
            }
            return source;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new MediaExtractException("Поиск исходного видео прерван", error);
        } catch (MediaExtractException error) { throw error; }
        catch (Exception error) { throw new MediaExtractException("Не удалось получить источник из Яндекс-превью. Откройте исходный видеосайт.", error); }
    }

    static String sourceFromHtml(String html) {
        var matcher = SOURCE.matcher(html);
        while (matcher.find()) {
            String source = HtmlUtils.htmlUnescape(matcher.group(1));
            try {
                MediaStreamService.validUrl(source);
                URI uri = URI.create(source);
                if (uri.getHost().endsWith("yandex.ru")) continue;
                if (java.util.Set.of("vk.com", "www.vk.com", "m.vk.com", "vkvideo.ru", "www.vkvideo.ru").contains(uri.getHost())
                        && uri.getPath().matches("/video-?\\d+_\\d+")) return "https://vkvideo.ru" + uri.getPath();
                return source;
            } catch (IllegalArgumentException ignored) { /* ignore invalid source attributes */ }
        }
        throw new MediaExtractException("В Яндекс-превью не найден источник видео. Откройте исходный видеосайт.");
    }
}
