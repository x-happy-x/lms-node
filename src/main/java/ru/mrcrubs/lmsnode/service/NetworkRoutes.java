package ru.mrcrubs.lmsnode.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import ru.mrcrubs.lmsnode.model.JobType;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Private, server-provisioned proxy registry. Clients can select an id, never a proxy URL. */
@Component
public final class NetworkRoutes {
    public record Choice(String id, String label, boolean available, List<String> supportedTypes, String scope) {}
    public record Choices(boolean enabled, boolean networkRoutesSupported, List<Choice> routes) {}
    public static final List<String> TYPES = List.of("DIRECT", "YTDLP", "ARIA2C");
    private final Map<String, Route> routes;
    public record Entry(String id, String label, String proxyUrl) {}
    public record Registry(List<Entry> routes) {}

    @org.springframework.beans.factory.annotation.Autowired
    public NetworkRoutes(@Value("${NODE_NETWORK_ROUTES_FILE:}") String file) {
        this(load(file));
    }
    public NetworkRoutes(List<Entry> entries) {
        Map<String,Route> values = new LinkedHashMap<>();
        for (Entry item : entries) {
            validId(item.id());
            if (item.id() == null || item.id().isEmpty() || item.label() == null || item.label().isBlank() || item.label().length() > 160)
                throw new IllegalArgumentException("Invalid network route definition");
            Route route = new Route(item.id(), item.label(), item.proxyUrl());
            if (values.putIfAbsent(item.id(), route) != null) throw new IllegalArgumentException("Duplicate network route id");
        }
        routes = Collections.unmodifiableMap(values);
    }
    private static List<Entry> load(String file) {
        if (file == null || file.isBlank()) return List.of();
        try {
            Path path = Path.of(file);
            if (Files.size(path) > 65536) throw new IllegalArgumentException();
            var registry = JsonMapper.builder().build().readValue(Files.readAllBytes(path), Registry.class);
            if (registry.routes() == null || registry.routes().size() > 32) throw new IllegalArgumentException();
            return registry.routes();
        } catch (Exception ignored) { throw new IllegalStateException("Cannot load private network routes registry"); }
    }
    public static NetworkRoutes disabled() { return new NetworkRoutes(List.of()); }
    public static void validId(String id) {
        if (id != null && !id.isEmpty() && !id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid network route id");
    }
    public Route resolve(String id) {
        validId(id);
        if (id == null || id.isEmpty()) return null;
        Route result = routes.get(id);
        if (result == null) throw new IllegalArgumentException("Selected network route is unavailable on this node");
        return result;
    }
    public Route resolve(String id, JobType type) {
        Route route = resolve(id);
        if (route != null && type == JobType.TORRENT) throw new IllegalArgumentException("Selected network routes do not support TORRENT");
        return route;
    }
    public Choices choices() {
        return new Choices(!routes.isEmpty(), true, routes.values().stream().map(route -> new Choice(route.id, route.label, true, TYPES, "download")).toList());
    }
    public static String redact(String value) {
        return value == null ? null : value.replaceAll("(?i)(https?://)[^\\s/@]+@", "$1[redacted]@")
                .replaceAll("(?im)(Proxy-Authorization:\\s*)[^\\r\\n]+", "$1[redacted]");
    }
    public static final class Route {
        public final String id, label;
        private final URI proxy;
        private final String username, password;
        public Route(String id, String label, String proxyUrl) {
            this.id = id; this.label = label;
            try {
                proxy = URI.create(proxyUrl);
                if (!"http".equals(proxy.getScheme()) || proxy.getHost() == null || proxy.getPort() < 1 || proxy.getPort() > 65535 ||
                        proxy.getRawQuery() != null || proxy.getRawFragment() != null || (proxy.getPath() != null && !proxy.getPath().isEmpty() && !"/".equals(proxy.getPath()))) throw new IllegalArgumentException();
                String userInfo = proxy.getRawUserInfo();
                if (userInfo == null || !userInfo.contains(":")) throw new IllegalArgumentException();
                String[] parts = userInfo.split(":", 2);
                username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                if (username.isBlank() || password.isBlank() || username.length() > 256 || password.length() > 1024) throw new IllegalArgumentException();
            } catch (Exception ignored) { throw new IllegalArgumentException("Invalid private HTTP proxy definition"); }
        }
        public String toolProxyUrl() { return proxy.toString(); }
        public HttpClient client(Duration timeout) {
            // The authenticator below is limited to this exact proxy, including CONNECT.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            var address = new InetSocketAddress(proxy.getHost(), proxy.getPort());
            return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(new ProxySelector() {
                        public List<Proxy> select(URI uri) { return List.of(new Proxy(Proxy.Type.HTTP, address)); }
                        public void connectFailed(URI uri, SocketAddress socket, java.io.IOException error) { /* never fall back direct */ }
                    }).authenticator(new Authenticator() {
                        protected PasswordAuthentication getPasswordAuthentication() {
                            if (getRequestorType() == RequestorType.PROXY && proxy.getHost().equalsIgnoreCase(getRequestingHost()) && getRequestingPort() == proxy.getPort())
                                return new PasswordAuthentication(username, password.toCharArray());
                            return null;
                        }
                    }).build();
        }
        @Override public String toString() { return "NetworkRoute[" + id + "]"; }
    }
}
