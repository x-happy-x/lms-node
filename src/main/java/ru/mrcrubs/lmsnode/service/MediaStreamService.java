package ru.mrcrubs.lmsnode.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Read-only inspection and in-memory remux sessions. Never creates a job or a media file. */
@Service
public class MediaStreamService {
    static final long SESSION_MILLIS = Duration.ofHours(2).toMillis();
    private final String ytdlp, ffmpeg;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Semaphore inspections = new Semaphore(2), streams = new Semaphore(2);
    private final Map<String, Media> cache = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public record Request(String url, Integer maxHeight, Integer maxFps, Boolean direct) {}
    public record VariantInfo(int height, int fps, Long sizeBytes, boolean sizeEstimated, String quality) {}
    public record Info(String title, Double durationSeconds, int height, int fps, Long sizeBytes,
                       boolean sizeEstimated, String quality, List<VariantInfo> variants) {}
    public record Preview(String sessionId, long expiresAt, Info info) {}
    record Input(String url, Map<String, String> headers) {}
    record Variant(int height, int fps, Long size, boolean estimated, Input video, Input audio, double score) {
        VariantInfo view() { return new VariantInfo(height, fps, size, estimated, height > 0 ? height + "p" + (fps > 30 ? " · " + fps + " fps" : "") : "Исходное"); }
    }
    record Media(String title, Double duration, List<Variant> variants, long until) {}
    record Session(Media media, Variant selected, long until) {}

    public MediaStreamService(@Value("${node.tools.ytdlp:yt-dlp}") String ytdlp,
                              @Value("${node.tools.ffmpeg:ffmpeg}") String ffmpeg) {
        this.ytdlp = ytdlp; this.ffmpeg = ffmpeg;
    }

    public Info inspect(Request request) { return view(media(request), request); }

    public Preview prepare(Request request) {
        Media media = media(request);
        Variant selected = choose(media, request.maxHeight(), request.maxFps());
        String id = UUID.randomUUID().toString(); long until = System.currentTimeMillis() + SESSION_MILLIS;
        synchronized (sessions) {
            sessions.entrySet().removeIf(e -> e.getValue().until < System.currentTimeMillis());
            if (sessions.size() >= 32) sessions.remove(sessions.keySet().iterator().next());
            sessions.put(id, new Session(media, selected, until));
        }
        return new Preview(id, until, view(media, request));
    }

    private Media media(Request request) {
        validUrl(request.url());
        if (request.maxHeight() != null && (request.maxHeight() < 144 || request.maxHeight() > 4320)) throw new IllegalArgumentException();
        if (request.maxFps() != null && (request.maxFps() < 1 || request.maxFps() > 240)) throw new IllegalArgumentException();
        synchronized (cache) {
            Media hit = cache.get(request.url());
            if (hit != null && hit.until > System.currentTimeMillis()) return hit;
        }
        if (!inspections.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Media inspection is busy");
        try {
            Media result;
            try { result = parse(runInspection(request.url()), request.url()); }
            catch (MediaExtractException error) {
                if (!Boolean.TRUE.equals(request.direct())) throw error;
                result = new Media("Видео", null, List.of(new Variant(0, 0, null, true, new Input(request.url(), Map.of()), null, 0)), System.currentTimeMillis() + 600_000);
            }
            synchronized (cache) {
                cache.entrySet().removeIf(e -> e.getValue().until < System.currentTimeMillis());
                if (cache.size() >= 64) cache.remove(cache.keySet().iterator().next());
                cache.put(request.url(), result);
            }
            return result;
        } finally { inspections.release(); }
    }

    List<String> inspectionCommand(String url) {
        return List.of(ytdlp, "-J", "--skip-download", "--no-playlist", "--no-warnings", "--no-progress", "--socket-timeout", "12", "--", url);
    }

    private String runInspection(String url) {
        Process process = null;
        try {
            process = new ProcessBuilder(inspectionCommand(url)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = process;
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] bytes = in.readNBytes(8 * 1024 * 1024 + 1);
                    if (bytes.length > 8 * 1024 * 1024) { running.destroyForcibly(); throw new IOException("Inspection response too large"); }
                    return bytes;
                } catch (IOException error) { throw new CompletionException(error); }
            });
            if (!process.waitFor(60, TimeUnit.SECONDS)) throw new MediaExtractException("Источник не ответил за 60 секунд");
            if (process.exitValue() != 0) throw new MediaExtractException("Не удалось получить форматы видео. Источник может требовать вход или ограничивать доступ ноды.");
            return new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (MediaExtractException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new MediaExtractException("Просмотр прерван", error); }
        catch (Exception error) { throw new MediaExtractException("Не удалось прочитать сведения о видео", error); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }

    Media parse(String json, String requestedUrl) {
        Map<String, Object> info;
        try { info = mapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception error) { throw new MediaExtractException("Invalid media metadata", error); }
        List<Map<String, Object>> formats = new ArrayList<>();
        if (info.get("formats") instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked") Map<String,Object> f = (Map<String,Object>)m; formats.add(f);
        }
        if (formats.isEmpty() && http(string(info, "url"))) formats.add(info);
        Double duration = number(info, "duration");
        Map<String,Object> audio = formats.stream().filter(f -> "none".equals(string(f,"vcodec")) && http(string(f,"url")))
                .max(Comparator.comparingDouble(f -> score(f, false))).orElse(null);
        Map<Integer,Variant> variants = new TreeMap<>();
        for (Map<String,Object> format : formats) {
            String url = string(format,"url");
            if (!http(url) || "none".equals(string(format,"vcodec"))) continue;
            int height = integer(format,"height"), fps = integer(format,"fps");
            boolean hasAudio = !"none".equals(string(format,"acodec")) && string(format,"acodec") != null;
            Map<String,Object> separateAudio = hasAudio ? null : audio;
            Long videoSize = size(format, duration), audioSize = separateAudio == null ? 0L : size(separateAudio, duration);
            Long total = videoSize == null || audioSize == null ? null : videoSize + audioSize;
            Variant variant = new Variant(height, fps, total, true, input(format, info), separateAudio == null ? null : input(separateAudio, info), score(format,true));
            int key=height*1000+fps;
            if (!variants.containsKey(key) || variant.score > variants.get(key).score) variants.put(key,variant);
        }
        if (variants.isEmpty()) throw new MediaExtractException("Нет доступного видеопотока");
        return new Media(Objects.requireNonNullElse(string(info,"title"),"Видео"), duration, List.copyOf(variants.values()), System.currentTimeMillis()+600_000);
    }

    private static double score(Map<String,Object> f, boolean video) {
        String codec = Objects.requireNonNullElse(string(f, video ? "vcodec":"acodec"),"");
        double compatible = video ? (codec.startsWith("avc") || codec.startsWith("h264") ? 1_000_000 : 0) : (codec.startsWith("mp4a") || codec.startsWith("aac") ? 1_000_000 : 0);
        return compatible + Objects.requireNonNullElse(number(f,"tbr"), Objects.requireNonNullElse(number(f,"abr"),0.0));
    }
    private static Input input(Map<String,Object> format, Map<String,Object> info) {
        String url=string(format,"url"); validUrl(url);
        Map<String,String> headers=new LinkedHashMap<>();
        for (Map<String,Object> source : List.of(info,format)) if (source.get("http_headers") instanceof Map<?,?> raw) {
            for (String name : List.of("User-Agent","Referer","Origin")) if (raw.get(name) instanceof String value && value.length()<=4096 && !value.contains("\r") && !value.contains("\n")) headers.put(name,value);
        }
        return new Input(url,Map.copyOf(headers));
    }
    private static Long size(Map<String,Object> f, Double duration) {
        Double value=number(f,"filesize"); if(value==null)value=number(f,"filesize_approx");
        if(value==null && duration!=null && number(f,"tbr")!=null)value=number(f,"tbr")*1000/8*duration;
        return value!=null && value>0 && value<Long.MAX_VALUE ? Math.round(value) : null;
    }
    private static Double number(Map<String,Object> m,String key){Object v=m.get(key);return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue():null;}
    private static int integer(Map<String,Object> m,String key){return (int)Math.round(Objects.requireNonNullElse(number(m,key),0.0));}
    private static String string(Map<String,Object> m,String key){return m.get(key) instanceof String s ? s:null;}
    private static boolean http(String value){try{validUrl(value);return true;}catch(Exception ignored){return false;}}
    static void validUrl(String value){
        if(value==null || value.length()>16384)throw new IllegalArgumentException();
        URI uri=URI.create(value);
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)throw new IllegalArgumentException();
    }
    private static Variant choose(Media media,Integer height,Integer fps){
        return media.variants.stream().filter(v -> (height==null || v.height<=height) && (fps==null || v.fps<=fps))
                .max(Comparator.comparingInt(Variant::height).thenComparingDouble(Variant::score))
                .orElseGet(() -> media.variants.stream().min(Comparator.comparingInt(Variant::height)).orElseThrow());
    }
    private static Info view(Media media,Request request){Variant v=choose(media,request.maxHeight(),request.maxFps());return new Info(media.title,media.duration,v.height,v.fps,v.size,v.estimated,v.view().quality,media.variants.stream().map(Variant::view).toList());}

    List<String> streamCommand(Variant variant,int startSeconds){
        List<String> cmd=new ArrayList<>(List.of(ffmpeg,"-hide_banner","-loglevel","error","-nostdin"));
        for(Input input:variant.audio==null?List.of(variant.video):List.of(variant.video,variant.audio)){
            if(startSeconds>0)cmd.addAll(List.of("-ss",String.valueOf(startSeconds)));
            cmd.addAll(List.of("-rw_timeout","20000000"));
            if(!input.headers.isEmpty())cmd.addAll(List.of("-headers",input.headers.entrySet().stream().map(e->e.getKey()+": "+e.getValue()+"\r\n").reduce("",String::concat)));
            cmd.addAll(List.of("-i",input.url));
        }
        cmd.addAll(List.of("-map","0:v:0?","-map",variant.audio==null?"0:a:0?":"1:a:0?","-c:v","copy","-c:a","aac","-b:a","160k","-movflags","frag_keyframe+empty_moov+default_base_moof","-frag_duration","2000000","-flush_packets","1","-f","mp4","pipe:1"));
        return cmd;
    }

    public void stream(String id,int height,int fps,int startSeconds,HttpServletResponse response) throws IOException {
        Session session; synchronized(sessions){session=sessions.get(id);}
        if(session==null || session.until<System.currentTimeMillis())throw new ResponseStatusException(HttpStatus.GONE,"Preview expired");
        if(startSeconds<0 || startSeconds>604800 || (session.media.duration!=null && startSeconds>=session.media.duration))throw new IllegalArgumentException();
        if(fps<0 || fps>240)throw new IllegalArgumentException();
        Variant variant=height==0?session.selected:session.media.variants.stream().filter(v->v.height==height&&(fps==0||v.fps==fps)).max(Comparator.comparingDouble(Variant::score)).orElseThrow(IllegalArgumentException::new);
        if(!streams.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Two previews are already streaming");
        Process process=null;
        try{
            process=new ProcessBuilder(streamCommand(variant,startSeconds)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try(InputStream input=process.getInputStream()){
                byte[] first=input.readNBytes(1024);
                if(first.length==0)throw new MediaExtractException("Не удалось открыть поток через FFmpeg");
                response.setStatus(200);response.setContentType("video/mp4");response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");response.setHeader("Accept-Ranges","none");
                OutputStream output=response.getOutputStream();output.write(first);output.flush();input.transferTo(output);output.flush();
            }
        }finally{if(process!=null && process.isAlive())process.destroyForcibly();streams.release();}
    }
}
