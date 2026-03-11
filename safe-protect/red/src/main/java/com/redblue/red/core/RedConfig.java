package com.redblue.red.core;

/**
 * 纯内存配置 — 不再使用 ForgeConfigSpec，避免生成 redteam-client.toml 文件。
 */
public class RedConfig {

    // 模拟原来 ForgeConfigSpec.ConfigValue 的接口，让调用方无需改动
    public static final ConfigValue<String> REMOTE_URL = new ConfigValue<>("https://your-server.com/api/attack.jar");
    public static final ConfigValue<String> AUTH_TOKEN = new ConfigValue<>("");

    public static class ConfigValue<T> {
        private volatile T value;
        ConfigValue(T defaultValue) { this.value = defaultValue; }
        public T get() { return value; }
        public void set(T v) { this.value = v; }
    }
}
