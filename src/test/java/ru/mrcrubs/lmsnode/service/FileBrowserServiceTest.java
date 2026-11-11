package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileBrowserServiceTest {
    @TempDir Path root;
    private FileBrowserService browser() {
        var jobs=mock(JobService.class);
        when(jobs.getBaseDownloadDir()).thenReturn(root);
        when(jobs.listStorageTargets(null)).thenReturn(List.of(new StorageTarget(root,1,1,true,null)));
        try { doAnswer(call->{Path source=call.getArgument(0),target=call.getArgument(1);if(target==null)Files.delete(source);else Files.move(source,target);return null;}).when(jobs).mutateStoredFile(any(),any()); }
        catch(java.io.IOException error){throw new AssertionError(error);}
        return new FileBrowserService(jobs);
    }
    @Test void listsUnicodeFilesFoldersAndParentsWithoutHiddenEntries() throws Exception {
        Files.createDirectory(root.resolve("Уроки"));Files.writeString(root.resolve("видео.mp4"),"1234");Files.writeString(root.resolve(".credentials"),"private");
        var result=browser().browse(root.toString(),"");
        assertEquals(List.of("Уроки","видео.mp4"),result.entries().stream().map(FileBrowserService.Entry::name).toList());
        assertTrue(result.entries().getFirst().directory());assertEquals(4L,result.entries().getLast().sizeBytes());
        assertEquals("video/mp4",result.entries().getLast().mimeType());assertNull(result.parentPath());
        assertEquals("",browser().browse(root.toString(),"Уроки").parentPath());
        assertEquals(root.resolve("видео.mp4").toRealPath(),browser().file(null,"видео.mp4").path());
    }
    @Test void rejectsTraversalOtherRootsHiddenFilesAndNonFiles() throws Exception {
        Files.writeString(root.resolve(".secret"),"private");Files.createDirectory(root.resolve("folder"));
        for(String path:List.of("../etc/passwd","folder/../../secret","/etc/passwd","C:/Windows/system.ini","folder\\file",".secret","folder/../file","folder/.secret"))
            assertThrows(ResponseStatusException.class,()->browser().file(root.toString(),path),path);
        assertThrows(ResponseStatusException.class,()->browser().browse(root.getParent().toString(),""));
        assertThrows(ResponseStatusException.class,()->browser().file(root.toString(),"folder"));
    }
    @Test void refusesSymbolicLinksEvenWhenTheTargetIsInsideTheStorage() throws Exception {
        Path actual=Files.writeString(root.resolve("actual.txt"),"data"),link=root.resolve("link.txt");
        try { Files.createSymbolicLink(link,actual); } catch(FileSystemException|UnsupportedOperationException error) { org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable on this host"); }
        assertThrows(ResponseStatusException.class,()->browser().file(root.toString(),"link.txt"));
        assertEquals(List.of("actual.txt"),browser().browse(root.toString(),"").entries().stream().map(FileBrowserService.Entry::name).toList());
    }
    @Test void boundsLargeDirectoryResponses() throws Exception {
        for(int i=0;i<FileBrowserService.MAX_ENTRIES+1;i++)Files.createFile(root.resolve("file-"+i));
        var result=browser().browse(null,"");assertEquals(FileBrowserService.MAX_ENTRIES,result.entries().size());assertTrue(result.truncated());
    }
    @Test void mutationsNeverOverwriteOrRecursivelyDelete() throws Exception {
        var files=browser();files.mutate("mkdir",null,"","Уроки");
        Files.writeString(root.resolve("lesson.txt"),"keep");Files.writeString(root.resolve("taken.txt"),"other");
        assertThrows(ResponseStatusException.class,()->files.mutate("rename",null,"lesson.txt","taken.txt"));
        assertEquals("keep",Files.readString(root.resolve("lesson.txt")));
        assertThrows(ResponseStatusException.class,()->files.mutate("delete",null,"",null));
        assertThrows(ResponseStatusException.class,()->files.mutate("mkdir",null,"","../outside"));
        Files.writeString(root.resolve("Уроки/child.txt"),"keep");
        assertThrows(ResponseStatusException.class,()->files.mutate("delete",null,"Уроки",null));
        files.mutate("rename",null,"lesson.txt","Новое имя.txt");assertTrue(Files.exists(root.resolve("Новое имя.txt")));
        files.mutate("delete",null,"Новое имя.txt",null);assertFalse(Files.exists(root.resolve("Новое имя.txt")));
        assertTrue(Files.exists(root.resolve("Уроки/child.txt")));
    }
}
