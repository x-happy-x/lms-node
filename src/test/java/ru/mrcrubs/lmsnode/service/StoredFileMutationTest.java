package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.config.NodeProperties;
import ru.mrcrubs.lmsnode.infra.InMemoryJobRepository;
import ru.mrcrubs.lmsnode.model.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StoredFileMutationTest {
    @TempDir Path root;
    @Test void renamingFolderUpdatesFinishedOutputsAndDeletingFileClearsItsReference() throws Exception {
        Path folder=Files.createDirectory(root.resolve("lesson")),file=Files.writeString(folder.resolve("video.mp4"),"data");
        var repository=new InMemoryJobRepository();var properties=new NodeProperties();properties.setDownloadDir(root.toString());
        var job=new DownloadJob(UUID.randomUUID(),JobType.DIRECT,"https://course.test/lesson",null,Instant.now());
        job.complete(Instant.now(),"Done",file.toString());repository.save(job);
        var service=new JobService(List.of(),properties,repository);
        try {
            Path target=root.resolve("renamed");service.mutateStoredFile(folder,target);
            assertEquals(target.resolve("video.mp4").toString(),job.getOutputPath());
            service.mutateStoredFile(target.resolve("video.mp4"),null);assertNull(job.getOutputPath());
        } finally {service.shutdown();}
    }
    @Test void activeJobsAndExistingDestinationsArePreserved() throws Exception {
        var repository=new InMemoryJobRepository();var properties=new NodeProperties();properties.setDownloadDir(root.toString());
        Path file=Files.writeString(root.resolve("active.mp4"),"keep");
        var job=new DownloadJob(UUID.randomUUID(),JobType.DIRECT,"https://course.test/lesson",null,Instant.now());job.setOutputPath(file.toString());repository.save(job);
        var service=new JobService(List.of(),properties,repository);
        try {assertThrows(IllegalStateException.class,()->service.mutateStoredFile(file,null));assertEquals("keep",Files.readString(file));}
        finally {service.shutdown();}
    }
}
