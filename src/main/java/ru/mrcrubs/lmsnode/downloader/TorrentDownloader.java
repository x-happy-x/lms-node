package ru.mrcrubs.lmsnode.downloader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.mrcrubs.lmsnode.model.JobType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BitTorrent downloads (magnet links and http(s) links to .torrent files) via aria2c.
 *
 * <p>Resume: aria2c keeps a {@code .aria2} control file next to the data and, for magnets,
 * saves the fetched metadata as {@code <infohash>.torrent}, so a paused, retried or
 * restarted job continues from the pieces it already has without refetching metadata.
 */
@Component
public class TorrentDownloader implements Downloader {
    private static final Pattern BTIH_HEX = Pattern.compile("xt=urn:btih:([0-9a-fA-F]{40})");

    private final String binary;
    private final long seedTimeMinutes;
    private final String listenPort;

    public TorrentDownloader(@Value("${node.tools.aria2c:aria2c}") String binary,
                             @Value("${node.torrent.seed-time-minutes:0}") long seedTimeMinutes,
                             @Value("${node.torrent.listen-port:6881-6999}") String listenPort) {
        this.binary = binary;
        this.seedTimeMinutes = Math.max(0, seedTimeMinutes);
        this.listenPort = listenPort;
    }

    @Override
    public JobType id() {
        return JobType.TORRENT;
    }

    public static boolean isMagnet(String url) {
        return url != null && url.regionMatches(true, 0, "magnet:?", 0, 8);
    }

    public static boolean looksLikeTorrent(String url) {
        if (url == null) {
            return false;
        }
        if (isMagnet(url)) {
            return true;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        String path = query >= 0 ? lower.substring(0, query) : lower;
        return path.endsWith(".torrent");
    }

    @Override
    public DownloadResult download(DownloadRequest request,
                                   DownloadExecutionContext context,
                                   Consumer<ProgressUpdate> progressConsumer) throws Exception {
        progressConsumer.accept(new ProgressUpdate(null, null, null, null,
                isMagnet(request.url()) ? "Fetching torrent metadata" : "Starting torrent"));

        Aria2cDownloader.Aria2Run run = Aria2cDownloader.Aria2Run.execute(
                buildCommand(request), request.downloadDir(), context, progressConsumer);
        if (run.exitCode() != 0) {
            throw new IllegalStateException(run.failureMessage());
        }

        Path outputPath = run.results().stream()
                .filter(Aria2Output.Result::ok)
                .map(result -> torrentRoot(request.downloadDir(), Path.of(result.path())))
                .filter(path -> path != null && Files.exists(path))
                .reduce((first, second) -> second)
                .orElse(null);
        deleteSavedMetadata(request);
        progressConsumer.accept(new ProgressUpdate(100.0, null, null, 0L, "Torrent download finished"));
        return new DownloadResult(outputPath == null ? null : outputPath.toString(), "Torrent download finished");
    }

    List<String> buildCommand(DownloadRequest request) {
        List<String> command = new ArrayList<>(Aria2cDownloader.commonArgs(binary, request.downloadDir()));
        command.add("--follow-torrent=mem");
        command.add("--bt-save-metadata=true");
        command.add("--bt-load-saved-metadata=true");
        command.add("--seed-time=" + seedTimeMinutes);
        command.add("--enable-dht=true");
        command.add("--bt-enable-lpd=true");
        command.add("--enable-peer-exchange=true");
        if (listenPort != null && !listenPort.isBlank()) {
            command.add("--listen-port=" + listenPort);
            command.add("--dht-listen-port=" + listenPort);
        }
        command.add(request.url());
        return command;
    }

    /**
     * aria2c reports the first file of a torrent; the job output is the top-level entry
     * under the download dir (the single file, or the torrent's directory).
     */
    static Path torrentRoot(Path downloadDir, Path reportedFile) {
        Path base = downloadDir.toAbsolutePath().normalize();
        Path file = reportedFile.toAbsolutePath().normalize();
        if (!file.startsWith(base) || file.equals(base)) {
            return null;
        }
        return base.resolve(base.relativize(file).getName(0));
    }

    private void deleteSavedMetadata(DownloadRequest request) {
        Matcher matcher = BTIH_HEX.matcher(request.url());
        if (!matcher.find()) {
            return;
        }
        try {
            Files.deleteIfExists(request.downloadDir().resolve(matcher.group(1).toLowerCase(Locale.ROOT) + ".torrent"));
        } catch (Exception ignored) {
            // The saved metadata file is only a resume helper.
        }
    }
}
