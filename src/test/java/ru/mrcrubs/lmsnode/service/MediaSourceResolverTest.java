package ru.mrcrubs.lmsnode.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MediaSourceResolverTest {
    @Test void selectedSourcePrecedesRecommendationsAndVkUsesCanonicalHttps() {
        String html = """
          <a href="https://other.test/recommendation">recommended</a>
          <a class="Link VideoViewer-SourcePathLink" href="http://vk.com/video-220754053_456244731?sh=4&amp;foo=1">VK</a>
          <a class="Link VideoViewer-SourcePathLink" href="https://other.test/unrelated">Later</a>
          """;
        assertEquals("https://vkvideo.ru/video-220754053_456244731",MediaSourceResolver.sourceFromHtml(html));
    }
    @Test void invalidAndMissingSourcesFailClearlyWithoutInventingUrls() {
        assertThrows(MediaExtractException.class,()->MediaSourceResolver.sourceFromHtml("<a class='VideoViewer-SourcePathLink' href='file:///private'>bad</a>"));
        assertThrows(MediaExtractException.class,()->MediaSourceResolver.sourceFromHtml("<script>data_raw is missing</script>"));
        assertEquals("https://vkvideo.ru/video-1_2",MediaSourceResolver.resolve("https://vkvideo.ru/video-1_2"));
    }
}
