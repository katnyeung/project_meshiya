package com.meshiya.controller;

import com.meshiya.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"})
@Tag(name = "Orders", description = "Food and drink order management endpoints")
public class OrderController {
    
    @Autowired
    private OrderService orderService;
    
    @Operation(summary = "Get order queue status", 
               description = "Returns current status of the order queue including pending orders, preparation times, and queue statistics")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Order status retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOrderStatus() {
        return ResponseEntity.ok(orderService.getQueueStatus());
    }
    
    @Operation(summary = "Complete an order", 
               description = "Marks an order as completed when customer finishes eating or drinking")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Order completed successfully"),
        @ApiResponse(responseCode = "404", description = "User or order not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/complete/{userId}")
    public ResponseEntity<String> completeOrder(
            @Parameter(description = "User ID of the customer") @PathVariable String userId) {
        orderService.completeOrder(userId);
        return ResponseEntity.ok("Order completed");
    }
}