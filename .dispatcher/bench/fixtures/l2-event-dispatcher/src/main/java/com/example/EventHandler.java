package com.example;

@FunctionalInterface
public interface EventHandler<T> {
    void handle(T event) throws Exception;
}
