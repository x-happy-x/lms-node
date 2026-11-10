package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MediaStreamServiceTest {
    private final MediaStreamService service = new MediaStreamService("yt-dlp", "ffmpeg");
    @Test void streamInspectionUsesTheSelectedProfile() {
        var command=service.inspectionCommand("https://course.test/watch","chrome");
        assertEquals("chrome",command.get(command.indexOf("--impersonate")+1));
        assertTrue(command.contains("--skip-download"));
    }
    @Test void formatsKeepResolutionFpsAndCombinedSizeWithoutExposingSourceUrls() throws Exception {
        var media = service.parse("""
            {"title":"Lesson","duration":100,"http_headers":{"User-Agent":"Browser","Cookie":"private"},"formats":[
              {"url":"https://cdn.test/audio?private=1","vcodec":"none","acodec":"mp4a.40.2","filesize":1000},
              {"url":"https://cdn.test/v720","height":720,"fps":30,"vcodec":"avc1","acodec":"none","filesize":9000},
              {"url":"https://cdn.test/v1080","height":1080,"fps":30,"vcodec":"avc1","acodec":"none","tbr":800},
              {"url":"https://cdn.test/v1080-60","height":1080,"fps":60,"vcodec":"avc1","acodec":"none","filesize":20000},
              {"url":"file:///etc/passwd","height":2160,"vcodec":"avc1"}]}
            """, "https://video.test/watch");
        assertEquals(3, media.variants().size());
        assertEquals(10000, media.variants().getFirst().size());
        assertEquals(10001000, media.variants().get(1).size());
        assertEquals(60, media.variants().getLast().fps());
        assertFalse(media.variants().getFirst().video().headers().containsKey("Cookie"));
        String publicJson = JsonMapper.builder().build().writeValueAsString(media.variants().stream().map(MediaStreamService.Variant::view).toList());
        assertFalse(publicJson.contains("cdn.test")); assertFalse(publicJson.contains("private"));
        assertTrue(publicJson.contains("sizeEstimated"));
    }
    @Test void remuxWritesOnlyStdoutAndSeeksEachInput() {
        var variant = new MediaStreamService.Variant(720,30,100L,true,
            new MediaStreamService.Input("https://cdn.test/video",Map.of("Referer","https://video.test")),
            new MediaStreamService.Input("https://cdn.test/audio",Map.of()),0);
        List<String> command = service.streamCommand(variant,42);
        assertEquals("pipe:1",command.getLast());
        assertEquals(2, command.stream().filter("-ss"::equals).count());
        assertEquals("copy",command.get(command.indexOf("-c:v")+1));
        assertTrue(command.contains("frag_keyframe+empty_moov+default_base_moof"));
        assertFalse(command.contains("-y")); assertFalse(command.contains("-P"));
    }
    @Test void inspectionCannotSaveOrRunArbitraryCommands() {
        var command=service.inspectionCommand("https://video.test/watch");
        assertTrue(command.contains("--skip-download")); assertTrue(command.contains("--no-playlist"));
        assertEquals(List.of("--","https://video.test/watch"),command.subList(command.size()-2,command.size()));
        for(String url:List.of("file:///etc/passwd","https://user:pass@video.test/watch","ftp://video.test/watch"))
            assertThrows(IllegalArgumentException.class,()->service.inspect(new MediaStreamService.Request(url,null,null,false)));
    }
}
