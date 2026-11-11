package ru.mrcrubs.lmsnode.controller;

import org.springframework.web.bind.annotation.*;
import ru.mrcrubs.lmsnode.service.NetworkRoutes;

@RestController
@RequestMapping("/api/network")
public class NetworkController {
    private final NetworkRoutes routes;
    public NetworkController(NetworkRoutes routes) { this.routes = routes; }
    @GetMapping("/routes") public NetworkRoutes.Choices routes() { return routes.choices(); }
}
