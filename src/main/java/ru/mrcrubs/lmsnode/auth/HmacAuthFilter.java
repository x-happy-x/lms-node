package ru.mrcrubs.lmsnode.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mrcrubs.lmsnode.config.HmacAuthProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class HmacAuthFilter extends OncePerRequestFilter {
    private static final String CLIENT_ID = "X-Client-Id";
    private static final String TIMESTAMP = "X-Timestamp";
    private static final String NONCE = "X-Nonce";
    private static final String BODY_SHA256 = "X-Body-Sha256";
    private static final String SIGNATURE = "X-Signature";

    private final HmacAuthProperties authProperties;
    private final NonceStore nonceStore;

    public HmacAuthFilter(HmacAuthProperties authProperties, NonceStore nonceStore) {
        this.authProperties = authProperties;
        this.nonceStore = nonceStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") && !uri.equals("/api/jobs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);

        String clientId = wrappedRequest.getHeader(CLIENT_ID);
        String timestampStr = wrappedRequest.getHeader(TIMESTAMP);
        String nonce = wrappedRequest.getHeader(NONCE);
        String bodyHashHeader = wrappedRequest.getHeader(BODY_SHA256);
        String signatureHeader = wrappedRequest.getHeader(SIGNATURE);

        if (!StringUtils.hasText(clientId)
                || !StringUtils.hasText(timestampStr)
                || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(bodyHashHeader)
                || !StringUtils.hasText(signatureHeader)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing auth headers");
            return;
        }

        String secret = authProperties.getClients().get(clientId);
        if (!StringUtils.hasText(secret)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Unknown client");
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException ex) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid timestamp");
            return;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > authProperties.getAllowedSkewSeconds()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Timestamp skew exceeded");
            return;
        }

        String nonceKey = clientId + ':' + nonce;
        if (nonceStore.isReplay(nonceKey, now, authProperties.getNonceTtlSeconds())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Replay detected");
            return;
        }

        String computedBodyHash = sha256Hex(wrappedRequest.getBody());
        if (!constantTimeEqualsHex(computedBodyHash, bodyHashHeader)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Body hash mismatch");
            return;
        }

        String payload = wrappedRequest.getMethod() + "\n"
                + wrappedRequest.getRequestURI() + "\n"
                + timestampStr + "\n"
                + nonce + "\n"
                + bodyHashHeader.toLowerCase();

        String expectedSignature;
        try {
            expectedSignature = hmacSha256Hex(secret, payload);
        } catch (Exception ex) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Signature calculation failed");
            return;
        }

        if (!constantTimeEqualsHex(expectedSignature, signatureHeader)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Signature mismatch");
            return;
        }

        filterChain.doFilter(wrappedRequest, response);
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash body", ex);
        }
    }

    private String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEqualsHex(String left, String right) {
        try {
            byte[] leftBytes = HexFormat.of().parseHex(left.toLowerCase());
            byte[] rightBytes = HexFormat.of().parseHex(right.toLowerCase());
            return MessageDigest.isEqual(leftBytes, rightBytes);
        } catch (Exception ex) {
            return false;
        }
    }
}
