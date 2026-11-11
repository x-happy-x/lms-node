package ru.mrcrubs.lmsnode.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** Read-only access to declared storage targets. Relative paths never follow symbolic links. */
@Service
public class FileBrowserService {
    static final int MAX_ENTRIES = 1000;
    private final JobService jobs;
    public record Entry(String name, String path, boolean directory, Long sizeBytes, Instant modifiedAt, String mimeType) {}
    public record Listing(String root, String path, String parentPath, List<Entry> entries, boolean truncated) {}
    public record File(Path path, String mimeType) {}
    public record Mutation(String path) {}

    public FileBrowserService(JobService jobs) { this.jobs = jobs; }

    public Listing browse(String selectedRoot, String relative) {
        Path root = root(selectedRoot), directory = resolve(root, relative);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw failure(HttpStatus.NOT_FOUND, "Папка не найдена");
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        int scanned = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (++scanned > MAX_ENTRIES * 4) { truncated = true; break; }
                if (!visible(child)) continue;
                try {
                    var attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory() && !attributes.isRegularFile()) continue;
                    if (entries.size() == MAX_ENTRIES) { truncated = true; break; }
                    entries.add(new Entry(child.getFileName().toString(), relative(root, child), attributes.isDirectory(),
                            attributes.isRegularFile() ? attributes.size() : null, attributes.lastModifiedTime().toInstant(),
                            attributes.isDirectory() ? null : mime(child)));
                } catch (IOException ignored) { /* Files can disappear while a download finishes. */ }
            }
        } catch (AccessDeniedException error) { throw failure(HttpStatus.FORBIDDEN, "Нет доступа к папке"); }
        catch (IOException error) { throw failure(HttpStatus.NOT_FOUND, "Папка недоступна"); }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::name));
        String path = relative(root, directory);
        return new Listing(root.toString(), path, path.isEmpty() ? null : relative(root, directory.getParent()), List.copyOf(entries), truncated);
    }

    public File file(String selectedRoot, String relative) {
        Path path = resolve(root(selectedRoot), relative);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw failure(HttpStatus.NOT_FOUND, "Файл не найден");
        return new File(path, mime(path));
    }

    public Mutation mutate(String operation, String selectedRoot, String relative, String name) {
        Path root=root(selectedRoot), source=resolve(root,relative);
        try {
            if (operation.equals("mkdir")) {
                if (!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)) throw failure(HttpStatus.NOT_FOUND,"Папка не найдена");
                Path target=source.resolve(name(name));Files.createDirectory(target);
                return new Mutation(relative(root,target));
            }
            if (source.equals(root)) throw failure(HttpStatus.FORBIDDEN,"Корень хранилища нельзя изменять");
            if(source.getFileName().toString().matches("(?i).*(?:\\.part(?:-frag\\d+)?|\\.aria2|\\.ytdl)$"))
                throw failure(HttpStatus.CONFLICT,"Файл используется загрузкой");
            if (operation.equals("rename")) {
                if (!Files.exists(source,LinkOption.NOFOLLOW_LINKS)) throw failure(HttpStatus.NOT_FOUND,"Файл не найден");
                Path target=source.resolveSibling(name(name));
                if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw failure(HttpStatus.CONFLICT,"Такое имя уже существует");
                jobs.mutateStoredFile(source,target);
                return new Mutation(relative(root,target));
            }
            if (operation.equals("delete")) {
                jobs.mutateStoredFile(source,null); // Never recursively delete a directory.
                return new Mutation(relative(root,source.getParent()));
            }
            throw failure(HttpStatus.BAD_REQUEST,"Неизвестное действие");
        } catch (FileAlreadyExistsException|DirectoryNotEmptyException error) { throw failure(HttpStatus.CONFLICT,"Папка не пуста или имя уже существует"); }
        catch (NoSuchFileException error) { throw failure(HttpStatus.NOT_FOUND,"Файл не найден"); }
        catch (AccessDeniedException error) { throw failure(HttpStatus.FORBIDDEN,"Нет прав на изменение"); }
        catch (IllegalStateException error) { throw failure(HttpStatus.CONFLICT,error.getMessage()); }
        catch (IOException error) { throw failure(HttpStatus.BAD_REQUEST,"Не удалось изменить файл"); }
    }
    private static String name(String value) {
        if (value==null || value.isBlank() || value.length()>255 || value.startsWith(".") || value.contains("/") || value.contains("\\") || value.contains(":") || value.chars().anyMatch(c->c<32||c==127))
            throw failure(HttpStatus.BAD_REQUEST,"Нужно имя без пути");
        return value;
    }

    private Path root(String selected) {
        Path wanted;
        try { wanted = (selected == null || selected.isBlank() ? jobs.getBaseDownloadDir() : Path.of(selected)).toAbsolutePath().normalize(); }
        catch (InvalidPathException error) { throw failure(HttpStatus.BAD_REQUEST, "Некорректная папка"); }
        boolean allowed = jobs.listStorageTargets(null).stream().map(target -> target.path().toAbsolutePath().normalize()).anyMatch(wanted::equals);
        if (!allowed || Files.isSymbolicLink(wanted)) throw failure(HttpStatus.FORBIDDEN, "Папка не разрешена");
        try { return wanted.toRealPath(); }
        catch (IOException error) { throw failure(HttpStatus.NOT_FOUND, "Хранилище недоступно"); }
    }

    private static Path resolve(Path root, String value) {
        String raw = value == null ? "" : value;
        if (raw.length() > 4096 || raw.startsWith("/") || raw.contains("\\") || raw.contains(":") || raw.chars().anyMatch(c -> c < 32 || c == 127))
            throw failure(HttpStatus.BAD_REQUEST, "Нужен относительный путь");
        Path result = root;
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty()) continue;
            if (part.startsWith(".")) throw failure(HttpStatus.FORBIDDEN, "Этот путь недоступен");
            try { result = result.resolve(part); }
            catch (InvalidPathException error) { throw failure(HttpStatus.BAD_REQUEST, "Некорректный путь"); }
            if (!visible(result)) throw failure(HttpStatus.FORBIDDEN, "Этот путь недоступен");
        }
        if (!result.normalize().startsWith(root)) throw failure(HttpStatus.FORBIDDEN, "Этот путь недоступен");
        return result;
    }

    private static boolean visible(Path path) {
        String name=path.getFileName().toString();
        if (name.startsWith(".") || name.contains("\\") || name.contains(":") || name.chars().anyMatch(c->c<32||c==127) || Files.isSymbolicLink(path)) return false;
        try { return !Files.isHidden(path); }
        catch (IOException error) { return false; }
    }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static String mime(Path path) { return MediaTypeFactory.getMediaType(path.getFileName().toString()).map(Object::toString).orElse("application/octet-stream"); }
    private static ResponseStatusException failure(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
