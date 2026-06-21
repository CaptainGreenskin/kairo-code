package io.kairo.code.server;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication(
    scanBasePackages = "io.kairo.code",
    exclude = org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration.class
)
public class KairoCodeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KairoCodeServerApplication.class, args);
    }

    @EventListener
    public void onServerReady(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        List<String> ips = getLanIps();
        System.out.println();
        System.out.println("  ✅ kairo-code ready");
        System.out.println("  → Local:   http://localhost:" + port);
        if (!ips.isEmpty()) {
            System.out.println("  → Network: http://" + ips.get(0) + ":" + port);
        }
        System.out.println();
        System.out.println("  Scan the QR code on the login page for mobile access.");
        System.out.println();
    }

    private static List<String> getLanIps() {
        List<String> ips = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return ips;
    }
}
