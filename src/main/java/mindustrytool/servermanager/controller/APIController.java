package mindustrytool.servermanager.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping()
@RequiredArgsConstructor
public class APIController {

    @GetMapping()
    public String getServers() {
        return "pong";
    }
}
