package ru.mrcrubs.lmsnode.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import ru.mrcrubs.lmsnode.service.MediaStreamService;
import java.io.IOException;

@RestController
@RequestMapping("/api/media")
public class MediaStreamController {
    private final MediaStreamService service;
    private final ru.mrcrubs.lmsnode.service.YtDlpNetworkService network;
    public MediaStreamController(MediaStreamService service, ru.mrcrubs.lmsnode.service.YtDlpNetworkService network){this.service=service;this.network=network;}
    @GetMapping("/capabilities") public ru.mrcrubs.lmsnode.service.YtDlpNetworkService.Capabilities capabilities(){return network.capabilities();}
    @PostMapping("/diagnose") public ru.mrcrubs.lmsnode.service.YtDlpNetworkService.Result diagnose(@RequestBody ru.mrcrubs.lmsnode.service.YtDlpNetworkService.Request request){return network.diagnose(request);}
    @PostMapping("/info") public MediaStreamService.Info info(@RequestBody MediaStreamService.Request request){return service.inspect(request);}
    @PostMapping("/preview") public MediaStreamService.Preview preview(@RequestBody MediaStreamService.Request request){return service.prepare(request);}
    @GetMapping("/preview/{id}") public void stream(@PathVariable String id,@RequestParam(defaultValue="0") int height,@RequestParam(defaultValue="0") int fps,@RequestParam(defaultValue="0") int start,HttpServletResponse response)throws IOException{
        service.stream(id,height,fps,start,response);
    }
}
