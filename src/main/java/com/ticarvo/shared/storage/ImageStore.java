package com.ticarvo.shared.storage;

/** Binary image storage; local disk today, an S3-compatible store later (ADR-007). Keys are server-generated paths. */
public interface ImageStore {
    void put(String key, byte[] content);
    byte[] get(String key);
    void delete(String key);
}
