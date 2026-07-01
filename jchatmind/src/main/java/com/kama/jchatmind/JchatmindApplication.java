package com.kama.jchatmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JchatmindApplication {

    public static void main(String[] args) {
        // 强制向下兼容 TLSv1.2 并优先使用 IPv4，解决 Java 17+ 调用国内大模型 API 时产生的 SSL 握手阻断问题 (Remote host terminated the handshake)
        System.setProperty("jdk.tls.client.protocols", "TLSv1.2");
        System.setProperty("java.net.preferIPv4Stack", "true");
        
        SpringApplication.run(JchatmindApplication.class, args);
    }
    //我在main加了行注释，测试rebase
}
