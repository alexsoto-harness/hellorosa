package com.example.hellorosa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Controller
public class HomeController {

    @Value("${HOSTNAME:unknown}")
    private String hostname;

    @Value("${DEPLOYMENT_METHOD:local}")
    private String deploymentMethod;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("hostname", getHostname());
        model.addAttribute("deploymentMethod", deploymentMethod);
        model.addAttribute("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        return "index";
    }

    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return hostname;
        }
    }
}
