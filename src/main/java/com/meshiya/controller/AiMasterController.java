package com.meshiya.controller;

import com.meshiya.scheduler.MasterResponseScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/master")
public class AiMasterController {
    
    @Autowired
    private MasterResponseScheduler masterResponseScheduler;
    
    @GetMapping("/status")
    public Map<String, Object> getMasterStatus() {
        return masterResponseScheduler.getStats();
    }
}