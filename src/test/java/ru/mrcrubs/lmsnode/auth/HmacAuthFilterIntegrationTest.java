package ru.mrcrubs.lmsnode.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mrcrubs.lmsnode.model.DownloadJob;
import ru.mrcrubs.lmsnode.model.JobType;
import ru.mrcrubs.lmsnode.service.JobService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HmacAuthFilterIntegrationTest {

    private static final String CLIENT_ID = "router-main";
    private static final String SECRET = "test-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    @Test
    void shouldAllowValidSignedCreateRequest() throws Exception {
        String body = "{\"type\":\"YTDLP\",\"url\":\"https://example.com/video\"}";
        UUID jobId = UUID.randomUUID();
        when(jobService.create(eq(JobType.YTDLP), eq("https://example.com/video"), isNull(), eq(true))).thenReturn(jobId);

        SignedHeaders signed = sign("POST", "/api/jobs", body, CLIENT_ID, SECRET, Instant.now().getEpochSecond(), UUID.randomUUID().toString());

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Client-Id", signed.clientId())
                        .header("X-Timestamp", signed.timestamp())
                        .header("X-Nonce", signed.nonce())
                        .header("X-Body-Sha256", signed.bodySha256())
                        .header("X-Signature", signed.signature()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void shouldRejectUnknownClient() throws Exception {
        String body = "{\"type\":\"DIRECT\",\"url\":\"https://example.com/file\"}";
        SignedHeaders signed = sign("POST", "/api/jobs", body, "unknown-client", SECRET, Instant.now().getEpochSecond(), UUID.randomUUID().toString());

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Client-Id", signed.clientId())
                        .header("X-Timestamp", signed.timestamp())
                        .header("X-Nonce", signed.nonce())
                        .header("X-Body-Sha256", signed.bodySha256())
                        .header("X-Signature", signed.signature()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectTimestampOutsideAllowedSkew() throws Exception {
        String body = "{\"type\":\"DIRECT\",\"url\":\"https://example.com/file\"}";
        long oldTimestamp = Instant.now().minusSeconds(1000).getEpochSecond();
        SignedHeaders signed = sign("POST", "/api/jobs", body, CLIENT_ID, SECRET, oldTimestamp, UUID.randomUUID().toString());

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Client-Id", signed.clientId())
                        .header("X-Timestamp", signed.timestamp())
                        .header("X-Nonce", signed.nonce())
                        .header("X-Body-Sha256", signed.bodySha256())
                        .header("X-Signature", signed.signature()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectReplayNonce() throws Exception {
        String body = "{\"type\":\"DIRECT\",\"url\":\"https://example.com/file\"}";
        UUID jobId = UUID.randomUUID();
        when(jobService.create(eq(JobType.DIRECT), eq("https://example.com/file"), isNull(), eq(true))).thenReturn(jobId);

        String nonce = UUID.randomUUID().toString();
        long timestamp = Instant.now().getEpochSecond();
        SignedHeaders signed = sign("POST", "/api/jobs", body, CLIENT_ID, SECRET, timestamp, nonce);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .headers(signed.toSpringHeaders()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .headers(signed.toSpringHeaders()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectBodyHashMismatch() throws Exception {
        String body = "{\"type\":\"DIRECT\",\"url\":\"https://example.com/file\"}";
        SignedHeaders signed = sign("POST", "/api/jobs", body, CLIENT_ID, SECRET, Instant.now().getEpochSecond(), UUID.randomUUID().toString());

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Client-Id", signed.clientId())
                        .header("X-Timestamp", signed.timestamp())
                        .header("X-Nonce", signed.nonce())
                        .header("X-Body-Sha256", "00" + signed.bodySha256().substring(2))
                        .header("X-Signature", signed.signature()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectSignatureMismatch() throws Exception {
        String body = "{\"type\":\"DIRECT\",\"url\":\"https://example.com/file\"}";
        SignedHeaders signed = sign("POST", "/api/jobs", body, CLIENT_ID, SECRET, Instant.now().getEpochSecond(), UUID.randomUUID().toString());

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Client-Id", signed.clientId())
                        .header("X-Timestamp", signed.timestamp())
                        .header("X-Nonce", signed.nonce())
                        .header("X-Body-Sha256", signed.bodySha256())
                        .header("X-Signature", "00" + signed.signature().substring(2)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowSignedGetWithEmptyBodyHash() throws Exception {
        UUID jobId = UUID.randomUUID();
        DownloadJob job = new DownloadJob(jobId, JobType.DIRECT, "https://example.com/file", Instant.now());
        when(jobService.get(jobId)).thenReturn(job);

        SignedHeaders signed = sign("GET", "/api/jobs/" + jobId, "", CLIENT_ID, SECRET, Instant.now().getEpochSecond(), UUID.randomUUID().toString());

        mockMvc.perform(get("/api/jobs/{jobId}", jobId)
                        .header("X-Client-Id", signed.clientId())
                        .header("X-Timestamp", signed.timestamp())
                        .header("X-Nonce", signed.nonce())
                        .header("X-Body-Sha256", signed.bodySha256())
                        .header("X-Signature", signed.signature()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    private SignedHeaders sign(String method,
                               String path,
                               String body,
                               String clientId,
                               String secret,
                               long timestamp,
                               String nonce) throws Exception {
        String bodySha256 = sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        String payload = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256;
        String signature = hmacSha256Hex(secret, payload);
        return new SignedHeaders(clientId, String.valueOf(timestamp), nonce, bodySha256, signature);
    }

    private String sha256Hex(byte[] input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(input));
    }

    private String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private record SignedHeaders(String clientId,
                                 String timestamp,
                                 String nonce,
                                 String bodySha256,
                                 String signature) {
        org.springframework.http.HttpHeaders toSpringHeaders() {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.add("X-Client-Id", clientId);
            headers.add("X-Timestamp", timestamp);
            headers.add("X-Nonce", nonce);
            headers.add("X-Body-Sha256", bodySha256);
            headers.add("X-Signature", signature);
            return headers;
        }
    }
}
