package com.example.notifications;

import java.util.List;

public interface ApplicationCache {
    List<String> get(List<String> keys);
    void put(String key,String value,int ttlSeconds,String group,int groupLimit);
    default void putAll(java.util.Map<String,String> values,int ttl,String group,int cap) {
        values.forEach((key,value)->put(key,value,ttl,group,cap));
    }
    default void cleanup() { }
    ApplicationCache NONE = new ApplicationCache() {
        public List<String> get(List<String> keys) { return keys.stream().map(k -> "").toList(); }
        public void put(String key,String value,int ttl,String group,int cap) { }
    };
}
