package com.meshiya.model;

public enum OrderStatus {
    ORDERED,      // Just placed the order
    PREPARING,    // Master is preparing the item
    READY,        // Ready to be served
    SERVED,       // Served to customer
    CONSUMING     // Customer is eating/drinking
}