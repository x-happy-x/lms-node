package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.model.YtDlpProfile;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class YtDlpNetworkServiceTest {
    @Test void supportedTargetsComeFromTheInstalledTransport() {
        var caps=YtDlpNetworkService.parseCapabilities("""
            [info] Available impersonate targets
            Client OS Source
            Chrome-152 Windows-10 curl_cffi
            Safari-27.0 Macos-27 curl_cffi
            Firefox-155 Windows-10 unavailable
            """);
        assertTrue(caps.impersonationSupported());
        assertTrue(caps.impersonationTargets().containsAll(List.of("auto","chrome","chrome-152:windows-10","safari","safari-27.0:macos-27")));
        assertFalse(caps.impersonationTargets().contains("firefox"));
        assertFalse(YtDlpNetworkService.parseCapabilities("No supported targets").impersonationSupported());
    }
    @Test void diagnosticsAcceptOnlyReadOnlyModesAndTypedProfiles() {
        var service=new YtDlpNetworkService("yt-dlp");
        var command=service.command(new YtDlpNetworkService.Request("formats","https://course.test/lesson?x=1&y=2","chrome",true));
        assertTrue(command.containsAll(List.of("--ignore-config","--no-cache-dir","--verbose","--skip-download","--no-playlist","-F","--impersonate")));
        assertEquals(List.of("--","https://course.test/lesson?x=1&y=2"),command.subList(command.size()-2,command.size()));
        assertFalse(command.contains("--exec"));
        assertTrue(service.command(new YtDlpNetworkService.Request("extract","https://course.test/list",null,false)).contains("--flat-playlist"));
        assertFalse(service.command(new YtDlpNetworkService.Request("version",null,"chrome",false)).contains("--impersonate"));
        assertThrows(IllegalArgumentException.class,()->service.command(new YtDlpNetworkService.Request("download","https://course.test",null,false)));
        assertThrows(IllegalArgumentException.class,()->service.command(new YtDlpNetworkService.Request("json","file:///etc/passwd",null,false)));
        for(String profile:List.of("chrome; rm -rf /","--exec=cmd","chrome --proxy=x","x".repeat(81)))assertThrows(IllegalArgumentException.class,()->YtDlpProfile.validate(profile));
    }
    @Test void outputIsDrainedBoundedAndExitCodeIsPreserved(@TempDir Path directory)throws Exception {
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        Path stub=directory.resolve(windows?"fake-ytdlp.cmd":"fake-ytdlp");
        String script=windows?"@echo off\r\nfor /l %%i in (1,1,9000) do echo repeated-output-for-limit\r\nexit /b 7\r\n":"#!/bin/sh\ni=0\nwhile [ \"$i\" -lt 9000 ]; do printf 'repeated-output-for-limit\\n'; i=$((i+1)); done\nexit 7\n";
        Files.writeString(stub,script);if(!windows)assertTrue(stub.toFile().setExecutable(true));
        var result=new YtDlpNetworkService(stub.toString()).diagnose(new YtDlpNetworkService.Request("version",null,null,false));
        assertEquals(7,result.exitCode());assertTrue(result.truncated());assertEquals(YtDlpNetworkService.OUTPUT_LIMIT,result.stdout().length());assertFalse(result.timedOut());
    }
}
