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
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
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
    @Test void selectedToolRoutesIgnoreAmbientProxyBypass() {
        var builder=NetworkRoutes.process(List.of("yt-dlp","--proxy","http://fixture:private@127.0.0.1:34000","https://course.test"));
        for(String key:List.of("NO_PROXY","no_proxy","HTTP_PROXY","http_proxy","HTTPS_PROXY","https_proxy","ALL_PROXY","all_proxy")) assertFalse(builder.environment().containsKey(key));
    }
    @Test void authenticatedHttpsConnectWorksAfterAnEarlierClientExists() throws Exception {
        String javaBinary=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process child=new ProcessBuilder(javaBinary,"-Djdk.http.auth.tunneling.disabledSchemes=","-Djdk.net.unixdomain.tmpdir="+System.getProperty("jdk.net.unixdomain.tmpdir",System.getProperty("java.io.tmpdir")),"-cp",System.getProperty("java.class.path"),ConnectProbe.class.getName()).redirectErrorStream(true).start();
        boolean ended=child.waitFor(20,java.util.concurrent.TimeUnit.SECONDS);
        if(!ended)child.destroyForcibly();
        assertTrue(ended,"CONNECT probe timed out");
        assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
    }
    public static class ConnectProbe {
        public static void main(String[] args) throws Exception {
            // A fresh JVM with the production startup flag. This client initializes JDK network internals first.
            java.net.http.HttpClient.newHttpClient();
            var authenticated=new java.util.concurrent.CountDownLatch(1);
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            try(var server=new java.net.ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(8000);
                String expected="Basic "+Base64.getEncoder().encodeToString("fixture:private".getBytes(StandardCharsets.UTF_8));
                Thread worker=Thread.ofVirtual().start(()->{
                    try {
                        for(int attempt=0;attempt<4;attempt++)try(var socket=server.accept()) {
                            socket.setSoTimeout(5000);
                            var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));
                            String request=reader.readLine();
                            if(request==null||!request.startsWith("CONNECT unresolvable.fixture.invalid:443"))throw new AssertionError("Expected HTTPS CONNECT through proxy");
                            String proxyAuth=null;boolean originAuth=false;
                            for(String line;(line=reader.readLine())!=null&&!line.isEmpty();) {
                                if(line.regionMatches(true,0,"Proxy-Authorization:",0,20))proxyAuth=line.substring(20).trim();
                                if(line.regionMatches(true,0,"Authorization:",0,14))originAuth=true;
                            }
                            if(originAuth)throw new AssertionError("Origin Authorization leaked to proxy request");
                            if(expected.equals(proxyAuth)) {
                                authenticated.countDown();socket.getOutputStream().write("HTTP/1.1 502 Fixture complete\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));return;
                            }
                            socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"LMS fixture\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        }
                    }catch(Throwable error){failure.set(error);}
                });
                var route=new NetworkRoutes.Route("fixture","HTTPS fixture","http://fixture:private@127.0.0.1:"+server.getLocalPort());
                try { route.client(Duration.ofSeconds(3)).send(java.net.http.HttpRequest.newBuilder(URI.create("https://unresolvable.fixture.invalid/lesson")).timeout(Duration.ofSeconds(8)).build(),java.net.http.HttpResponse.BodyHandlers.discarding()); }
                catch(java.io.IOException expectedFailure){ /* proxy stops before any TLS/origin connection */ }
                worker.join(9000);
                if(authenticated.getCount()!=0)throw new AssertionError("Proxy CONNECT authentication was not sent",failure.get());
            }
        }
    }
}
