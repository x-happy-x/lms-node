package ru.mrcrubs.lmsnode.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.mrcrubs.lmsnode.model.YtDlpProfile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Read-only, bounded diagnostic operations. No shell, downloads or caller-supplied flags. */
@Service
public class YtDlpNetworkService {
    static final int OUTPUT_LIMIT = 65536;
    private final String binary;
    private final NetworkRoutes routes;
    private final Semaphore diagnostics = new Semaphore(1);
    private final Set<Process> active = ConcurrentHashMap.newKeySet();
    private volatile Capabilities cached;
    private volatile long cacheUntil;
    public record Capabilities(boolean impersonationSupported, List<String> impersonationTargets, boolean diagnosticsSupported) {}
    public record Request(String mode, String url, String impersonate, Boolean verbose, String networkRouteId) {
        public Request(String mode, String url, String impersonate, Boolean verbose) { this(mode,url,impersonate,verbose,null); }
    }
    public record Result(List<String> command, int exitCode, String stdout, String stderr, boolean truncated,
                         boolean timedOut, long durationMillis) {}
    public YtDlpNetworkService(String binary) { this(binary,NetworkRoutes.disabled()); }
    @org.springframework.beans.factory.annotation.Autowired
    public YtDlpNetworkService(@Value("${node.tools.ytdlp:yt-dlp}") String binary, NetworkRoutes routes) { this.binary = binary; this.routes = routes; }

    public synchronized Capabilities capabilities() {
        if (cached != null && cacheUntil > System.currentTimeMillis()) return cached;
        try {
            Result output = run(List.of(binary,"--ignore-config","--no-colors","--list-impersonate-targets"), 8);
            cached = output.exitCode == 0 ? parseCapabilities(output.stdout) : new Capabilities(false,List.of(),true);
        } catch (IOException error) { cached = new Capabilities(false,List.of(),true); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Inspection interrupted"); }
        cacheUntil = System.currentTimeMillis() + 300_000;
        return cached;
    }
    static Capabilities parseCapabilities(String text) {
        var pattern = Pattern.compile("(?m)^(\\S+)\\s+(\\S+)\\s+curl_cffi\\s*$");
        var matcher = pattern.matcher(text);
        SortedSet<String> targets = new TreeSet<>();
        while (matcher.find()) {
            String client=matcher.group(1).toLowerCase(Locale.ROOT), os=matcher.group(2).toLowerCase(Locale.ROOT);
            String profile=client+":"+os;
            try { YtDlpProfile.validate(profile); targets.add(profile); targets.add(client.split("-",2)[0]); }
            catch (IllegalArgumentException ignored) { /* skip unsupported table rows */ }
        }
        if (!targets.isEmpty()) targets.add("auto");
        return new Capabilities(!targets.isEmpty(),List.copyOf(targets),true);
    }
    List<String> command(Request request) {
        if (request == null || request.mode == null) throw new IllegalArgumentException("Diagnostic mode is required");
        YtDlpProfile.validate(request.impersonate);
        List<String> command = new ArrayList<>(List.of(binary,"--ignore-config","--no-colors","--no-cache-dir"));
        if (Boolean.TRUE.equals(request.verbose)) command.add("--verbose");
        switch (request.mode) {
            case "version" -> command.add("--version");
            case "profiles" -> command.add("--list-impersonate-targets");
            case "formats", "json", "extract" -> {
                MediaStreamService.validUrl(request.url);
                var route = routes.resolve(request.networkRouteId);
                if (route != null) command.addAll(List.of("--proxy",route.toolProxyUrl()));
                command.addAll(List.of("--skip-download","--no-playlist","--no-progress","--socket-timeout","12",request.mode.equals("formats")?"-F":"-J"));
                if (request.mode.equals("extract")) { command.remove("--no-playlist");command.addAll(List.of("--flat-playlist","--playlist-end","300")); }
                YtDlpProfile.append(command,request.impersonate);
                command.addAll(List.of("--",request.url));
            }
            default -> throw new IllegalArgumentException("Unsupported diagnostic mode");
        }
        return command;
    }
    public Result diagnose(Request request) {
        List<String> command=command(request);
        if (!diagnostics.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"A diagnostic request is already running");
        try { return run(command,60); }
        catch (IOException error) { throw new MediaExtractException("Не удалось запустить yt-dlp на ноде",error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt();throw new MediaExtractException("Диагностика прервана",error); }
        finally { diagnostics.release(); }
    }
    private Result run(List<String> command,int timeoutSeconds) throws IOException,InterruptedException {
        long started=System.nanoTime();Process process=NetworkRoutes.process(command).start();active.add(process);
        Capture stdout=new Capture(process.getInputStream()),stderr=new Capture(process.getErrorStream());
        try {
            boolean done=process.waitFor(timeoutSeconds,TimeUnit.SECONDS);
            if (!done) { kill(process);process.waitFor(3,TimeUnit.SECONDS); }
            stdout.thread.join(3000);stderr.thread.join(3000);
            return new Result(command.stream().map(NetworkRoutes::redact).toList(),done?process.exitValue():124,stdout.text(),stderr.text(),stdout.truncated||stderr.truncated,!done,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        } finally { active.remove(process);if(process.isAlive())kill(process); }
    }
    static final class Capture {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private volatile boolean truncated;
        private final Thread thread;
        Capture(InputStream stream) {
            thread=Thread.ofVirtual().start(()->{
                try(stream) {
                    byte[] buffer=new byte[8192];int count;
                    while((count=stream.read(buffer))!=-1) synchronized(bytes) {
                        int keep=Math.min(count,OUTPUT_LIMIT-bytes.size());if(keep>0)bytes.write(buffer,0,keep);
                        if(keep<count)truncated=true;
                    }
                }catch(IOException ignored){ /* process ended or disconnected */ }
            });
        }
        String text() {
            synchronized(bytes) {
                return NetworkRoutes.redact(bytes.toString(StandardCharsets.UTF_8));
            }
        }
    }
    private static void kill(Process process) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly(); }
    @PreDestroy public void close() { active.forEach(YtDlpNetworkService::kill); }
}
