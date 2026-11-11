package ru.mrcrubs.lmsnode.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.downloader.*;
import ru.mrcrubs.lmsnode.infra.FileJobRepository;
import ru.mrcrubs.lmsnode.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NetworkRoutesTest {
    @Test void authenticatedProxyCarriesDirectDownloadWithoutDestinationDns(@TempDir Path directory) throws Exception {
        byte[] bytes = "synthetic-learning-material".getBytes(StandardCharsets.UTF_8);
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger authenticated = new AtomicInteger();
        String expected="Basic "+Base64.getEncoder().encodeToString("fixture-user:fixture-secret".getBytes(StandardCharsets.UTF_8));
        proxy.createContext("/", exchange -> {
            if(!expected.equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
                exchange.getResponseHeaders().set("Proxy-Authenticate","Basic realm=\"LMS fixture\"");exchange.sendResponseHeaders(407,-1);exchange.close();return;
            }
            assertEquals("unresolvable.fixture.invalid",exchange.getRequestURI().getHost());
            authenticated.incrementAndGet();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        proxy.start();
        try {
            var route = new NetworkRoutes.Route("fixture","Synthetic route","http://fixture-user:fixture-secret@127.0.0.1:"+proxy.getAddress().getPort());
            var request = new DownloadRequest(UUID.randomUUID(),JobType.DIRECT,"http://unresolvable.fixture.invalid/lesson.bin",directory,null,null,route);
            var result = new DirectHttpDownloader().download(request,new DownloadExecutionContext(),update->{});
            assertArrayEquals(bytes,Files.readAllBytes(Path.of(result.outputPath())));
            assertTrue(authenticated.get()>0);
        } finally { proxy.stop(0); }
    }
    @Test void failedSelectedProxyDoesNotFallBackToOrigin() throws Exception {
        var origin=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger hits=new AtomicInteger();
        origin.createContext("/",e->{hits.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});origin.start();
        int closedPort;try(var socket=new java.net.ServerSocket(0)){closedPort=socket.getLocalPort();}
        try {
            var route=new NetworkRoutes.Route("down","Offline route","http://fixture:private@127.0.0.1:"+closedPort);
            assertThrows(java.io.IOException.class,()->route.client(Duration.ofMillis(300)).send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+origin.getAddress().getPort()+"/lesson")).build(),java.net.http.HttpResponse.BodyHandlers.discarding()));
            assertEquals(0,hits.get());
        }finally{origin.stop(0);}
    }
    @Test void publicChoicesAndPersistedJobsNeverContainProxyCredentials(@TempDir Path directory) throws Exception {
        var routes=new NetworkRoutes(List.of(new NetworkRoutes.Entry("eu","Europe","http://fixture:secret-value@127.0.0.1:34999")));
        String json=JsonMapper.builder().build().writeValueAsString(routes.choices());
        assertFalse(json.contains("secret-value"));assertFalse(json.contains("proxyUrl"));assertTrue(json.contains("Europe"));
        var repository=new FileJobRepository(directory.resolve("jobs.json"));var job=new DownloadJob(UUID.randomUUID(),JobType.DIRECT,"https://course.test/lesson",Instant.now());job.setNetworkRoute("eu","Europe");repository.save(job);
        var loaded=new FileJobRepository(directory.resolve("jobs.json")).findById(job.getJobId()).orElseThrow();assertEquals("eu",loaded.getNetworkRouteId());assertEquals("Europe",loaded.getNetworkRouteLabel());
        assertFalse(Files.readString(directory.resolve("jobs.json")).contains("secret-value"));
        assertThrows(IllegalArgumentException.class,()->routes.resolve("eu",JobType.TORRENT));assertThrows(IllegalArgumentException.class,()->routes.resolve("unknown"));
        assertNull(routes.resolve(null));assertNull(new FileJobRepository(directory.resolve("empty.json")).findAll().stream().findFirst().orElse(null));
    }
    @Test void outputRedactionCoversArgvAndVerboseHeaders() {
        String value="--proxy http://fixture:secret-value@127.0.0.1:34000\nProxy-Authorization: Basic c2VjcmV0\n";
        String safe=NetworkRoutes.redact(value);assertFalse(safe.contains("secret-value"));assertFalse(safe.contains("c2VjcmV0"));assertTrue(safe.contains("127.0.0.1:34000"));
    }
    @Test void inspectionAndDiagnosticCommandsUseTheSelectedRoute() {
        var routes=new NetworkRoutes(List.of(new NetworkRoutes.Entry("eu","Europe","http://fixture:secret-value@127.0.0.1:34999")));
        var route=routes.resolve("eu");
        var inspect=new MediaStreamService("yt-dlp","ffmpeg",routes).inspectionCommand("https://course.test/lesson","chrome",route);
        assertEquals(route.toolProxyUrl(),inspect.get(inspect.indexOf("--proxy")+1));
        var extract=new MediaExtractService("yt-dlp",30,routes).buildCommand("https://course.test/list",null,route);
        assertEquals(route.toolProxyUrl(),extract.get(extract.indexOf("--proxy")+1));
        var diagnostic=new YtDlpNetworkService("yt-dlp",routes).command(new YtDlpNetworkService.Request("formats","https://course.test/lesson",null,true,"eu"));
        assertEquals(route.toolProxyUrl(),diagnostic.get(diagnostic.indexOf("--proxy")+1));
        assertThrows(IllegalArgumentException.class,()->new YtDlpNetworkService("yt-dlp",routes).command(new YtDlpNetworkService.Request("formats","https://course.test/lesson",null,false,"unknown")));
    }
}
