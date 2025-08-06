package com.meshiya.controller;

import com.meshiya.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"})
public class OrderController {
    
    @Autowired
    private OrderService orderService;
    
    /**
     * Gets current order queue status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOrderStatus() {
        return ResponseEntity.ok(orderService.getQueueStatus());
    }
    
    /**
     * Completes an order (customer finished eating/drinking)
     */
    @PostMapping("/complete/{userId}")
    public ResponseEntity<String> completeOrder(@PathVariable String userId) {
        orderService.completeOrder(userId);
        return ResponseEntity.ok("Order completed");
    }
}