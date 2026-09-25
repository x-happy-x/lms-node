package ru.mrcrubs.lmsnode.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.mrcrubs.lmsnode.api.DownloadOptionResponse;
import ru.mrcrubs.lmsnode.api.JobPreflightResponse;
import ru.mrcrubs.lmsnode.api.StorageTargetResponse;
import ru.mrcrubs.lmsnode.downloader.TorrentDownloader;
import ru.mrcrubs.lmsnode.model.JobType;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class DownloadPreflightService {
    private final JobService jobService;
    private final String ytdlpBinary;
    private final String aria2cBinary;

    public DownloadPreflightService(JobService jobService,
                                    @Value("${node.tools.ytdlp:yt-dlp}") String ytdlpBinary,
                                    @Value("${node.tools.aria2c:aria2c}") String aria2cBinary) {
        this.jobService = jobService;
        this.ytdlpBinary = ytdlpBinary;
        this.aria2cBinary = aria2cBinary;
    }

    public JobPreflightResponse preflight(String url) {
        boolean magnet = TorrentDownloader.isMagnet(url);
        boolean torrentLink = TorrentDownloader.looksLikeTorrent(url);
        DirectProbe directProbe = magnet
                ? new DirectProbe(false, false, false, null, false, "magnet links are downloaded as TORRENT")
                : probeDirect(url);
        boolean aria2cAvailable = isExecutableOnPath(aria2cBinary);
        boolean ytdlpAvailable = isExecutableOnPath(ytdlpBinary);

        List<DownloadOptionResponse> options = new ArrayList<>();
        options.add(new DownloadOptionResponse(
                JobType.DIRECT,
                directProbe.supported(),
                directProbe.resumeSupported(),
                directProbe.segmentedPossible(),
                directProbe.message()
        ));
        options.add(new DownloadOptionResponse(
                JobType.ARIA2C,
                aria2cAvailable && !magnet,
                directProbe.resumeSupported(),
                aria2cAvailable && directProbe.segmentedPossible(),
                aria2cAvailable
                        ? "aria2c is available on node; direct-link support inferred from HTTP probe"
                        : "aria2c binary is not available on node"
        ));
        options.add(new DownloadOptionResponse(
                JobType.TORRENT,
                aria2cAvailable && torrentLink,
                aria2cAvailable && torrentLink,
                aria2cAvailable && torrentLink,
                !aria2cAvailable
                        ? "aria2c binary is not available on node"
                        : torrentLink
                        ? "torrent is downloaded with aria2c; size is known after metadata is fetched"
                        : "url is not a magnet link or a .torrent file"
        ));
        options.add(new DownloadOptionResponse(
                JobType.YTDLP,
                ytdlpAvailable && !magnet,
                false,
                false,
                ytdlpAvailable
                        ? "yt-dlp is available on node; URL compatibility is not verified during preflight"
                        : "yt-dlp binary is not available on node"
        ));

        List<JobType> supportedTypes = options.stream()
                .filter(DownloadOptionResponse::supported)
                .map(DownloadOptionResponse::type)
                .toList();

        JobType recommendedType = null;
        if (torrentLink && aria2cAvailable) {
            recommendedType = JobType.TORRENT;
        } else if (magnet) {
            recommendedType = null;
        } else if (directProbe.supported()) {
            recommendedType = JobType.DIRECT;
        } else if (aria2cAvailable) {
            recommendedType = JobType.ARIA2C;
        } else if (ytdlpAvailable) {
            recommendedType = JobType.YTDLP;
        }

        Long requiredBytes = directProbe.sizeKnown() ? directProbe.sizeBytes() : null;
        List<StorageTargetResponse> storageTargets = jobService.listStorageTargets(requiredBytes).stream()
                .sorted(Comparator.comparing(StorageTarget::writable).reversed()
                        .thenComparingLong(StorageTarget::freeBytes).reversed())
                .map(target -> new StorageTargetResponse(
                        target.path().toString(),
                        target.freeBytes(),
                        target.totalBytes(),
                        target.writable(),
                        target.canFit()
                ))
                .toList();

        return new JobPreflightResponse(
                url,
                directProbe.sizeBytes(),
                directProbe.sizeKnown(),
                recommendedType,
                supportedTypes,
                options,
                jobService.getBaseDownloadDir().toString(),
                storageTargets
        );
    }

    private DirectProbe probeDirect(String url) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String headFailureMessage = null;
        try {
            HttpRequest headRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> headResp = client.send(headRequest, HttpResponse.BodyHandlers.discarding());
            if (headResp.statusCode() < 400) {
                Long sizeBytes = headResp.headers().firstValueAsLong("Content-Length").isPresent()
                        ? headResp.headers().firstValueAsLong("Content-Length").getAsLong()
                        : null;
                boolean sizeKnown = sizeBytes != null && sizeBytes > 0;
                boolean acceptsRange = acceptsRange(headResp.headers().firstValue("Accept-Ranges").orElse(null));
                if (acceptsRange) {
                    return new DirectProbe(true, true, sizeKnown, sizeBytes, sizeKnown, "HEAD probe succeeded");
                }
            } else {
                headFailureMessage = "HTTP probe failed: " + headResp.statusCode();
            }
        } catch (Exception ex) {
            headFailureMessage = "HTTP probe failed: " + ex.getMessage();
        }

        try {
            HttpRequest rangedRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Range", "bytes=0-0")
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(rangedRequest, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                if (headFailureMessage != null) {
                    return new DirectProbe(false, false, false, null, false, headFailureMessage + "; GET probe failed: " + resp.statusCode());
                }
                return new DirectProbe(false, false, false, null, false, "HTTP probe failed: " + resp.statusCode());
            }
            Long sizeBytes = extractSize(resp);
            boolean sizeKnown = sizeBytes != null && sizeBytes > 0;
            boolean resumeSupported = resp.statusCode() == 206 || acceptsRange(resp.headers().firstValue("Accept-Ranges").orElse(null));
            return new DirectProbe(true, resumeSupported, sizeKnown, sizeBytes, resumeSupported && sizeKnown,
                    resumeSupported ? "Range requests are supported" : "Direct download works, but resume support was not confirmed");
        } catch (Exception ex) {
            if (headFailureMessage != null) {
                return new DirectProbe(false, false, false, null, false, headFailureMessage + "; GET probe failed: " + ex.getMessage());
            }
            return new DirectProbe(false, false, false, null, false, "HTTP probe failed: " + ex.getMessage());
        }
    }

    private Long extractSize(HttpResponse<?> response) {
        String contentRange = response.headers().firstValue("Content-Range").orElse(null);
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < contentRange.length()) {
                try {
                    long total = Long.parseLong(contentRange.substring(slash + 1).trim());
                    if (total > 0) {
                        return total;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall back to content length.
                }
            }
        }
        return response.headers().firstValueAsLong("Content-Length").isPresent()
                ? response.headers().firstValueAsLong("Content-Length").getAsLong()
                : null;
    }

    private boolean acceptsRange(String value) {
        return value != null && value.equalsIgnoreCase("bytes");
    }

    private boolean isExecutableOnPath(String commandOrPath) {
        Path direct = Path.of(commandOrPath);
        if (direct.isAbsolute() || commandOrPath.contains("/") || commandOrPath.contains("\\")) {
            return Files.isExecutable(direct);
        }

        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }

        for (String entry : path.split(File.pathSeparator)) {
            Path candidate = Path.of(entry).resolve(commandOrPath);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private record DirectProbe(boolean supported,
                               boolean resumeSupported,
                               boolean sizeKnown,
                               Long sizeBytes,
                               boolean segmentedPossible,
                               String message) {
    }
}
