package ru.mrcrubs.lmsnode.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.mrcrubs.lmsnode.service.FileBrowserService;
import ru.mrcrubs.lmsnode.service.JobService;
import ru.mrcrubs.lmsnode.service.StorageTarget;
import java.nio.file.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageFilesControllerTest {
    @TempDir Path root;
    @Test void servesByteRangesAndHeadAndNeverInlinesHtml() throws Exception {
        Files.writeString(root.resolve("lesson.mp4"),"0123456789");Files.writeString(root.resolve("index.html"),"<script>alert(1)</script>");
        var jobs=mock(JobService.class);when(jobs.getBaseDownloadDir()).thenReturn(root);
        when(jobs.listStorageTargets(null)).thenReturn(List.of(new StorageTarget(root,1,1,true,null)));
        var mvc=MockMvcBuilders.standaloneSetup(new StorageFilesController(new FileBrowserService(jobs))).build();
        mvc.perform(get("/api/storage/file").param("path","lesson.mp4").param("inline","1").header("Range","bytes=2-5"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 2-5/10")).andExpect(content().string("2345"));
        mvc.perform(head("/api/storage/file").param("path","lesson.mp4")).andExpect(status().isOk()).andExpect(header().longValue("Content-Length",10)).andExpect(content().string(""));
        mvc.perform(get("/api/storage/file").param("path","index.html").param("inline","1"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(post("/api/storage/files/mkdir").contentType("application/json").content("{\"path\":\"\",\"name\":\"Newfolder\"}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(root.resolve("Newfolder")));
    }
}
