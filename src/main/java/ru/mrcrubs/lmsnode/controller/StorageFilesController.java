package ru.mrcrubs.lmsnode.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import ru.mrcrubs.lmsnode.service.FileBrowserService;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/storage")
public class StorageFilesController {
    private static final Set<String> INLINE_IMAGES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp", "image/x-icon", "image/vnd.microsoft.icon", "image/heic", "image/heif");
    private final FileBrowserService files;
    public StorageFilesController(FileBrowserService files) { this.files = files; }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> unavailable(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason()==null?"Файл недоступен":error.getReason()));
    }
    public record MutationRequest(String root,String path,String name) {}

    @PostMapping("/files/{operation:mkdir|rename|delete}")
    public FileBrowserService.Mutation mutate(@PathVariable String operation,@RequestBody MutationRequest request) {
        return files.mutate(operation,request.root(),request.path(),request.name());
    }

    @GetMapping("/browse")
    public FileBrowserService.Listing browse(@RequestParam(required = false) String root,
                                           @RequestParam(defaultValue = "") String path) {
        return files.browse(root, path);
    }

    /** Spring's resource converter handles Range/HEAD without buffering a whole file. */
    @GetMapping("/file")
    public ResponseEntity<Resource> file(@RequestParam(required = false) String root,
                                         @RequestParam(defaultValue = "") String path,
                                         @RequestParam(defaultValue = "0") String inline, HttpServletRequest request) throws Exception {
        var file = files.file(root, path);
        boolean preview = "1".equals(inline) && safeInline(file.mimeType());
        String disposition = (preview ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.path().getFileName().toString(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType()))
                .contentLength(Files.size(file.path())).lastModified(Files.getLastModifiedTime(file.path()).toMillis())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition).header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .cacheControl(CacheControl.noStore()).body(request.getMethod().equals("HEAD")?null:new FileSystemResource(file.path()));
    }
    private static boolean safeInline(String mime) {
        return mime.startsWith("video/") || mime.startsWith("audio/") ||
                INLINE_IMAGES.contains(mime) || mime.equals("application/pdf") || mime.equals("text/plain");
    }
}
