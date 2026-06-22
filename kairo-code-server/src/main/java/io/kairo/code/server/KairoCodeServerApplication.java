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
        // Preferred IPs: real LAN (192.168.x / 10.x / 172.16-31.x), excluding virtual adapters.
        List<String> preferred = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                String name = iface.getDisplayName() != null
                        ? iface.getDisplayName().toLowerCase() : "";
                // Skip known virtual adapters (VMware, VirtualBox, Docker, WSL, VPN, etc.)
                boolean virtual = name.contains("vmware") || name.contains("virtualbox")
                        || name.contains("docker") || name.contains("wsl")
                        || name.contains("vpn") || name.contains("tap") || name.contains("veth")
                        || name.contains("hyper-v") || name.contains("virtual");
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (virtual) {
                            others.add(ip);
                        } else if (isPrivateLan(ip)) {
                            preferred.add(ip);
                        } else {
                            others.add(ip);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        List<String> result = new ArrayList<>(preferred);
        result.addAll(others);
        return result;
    }

    private static boolean isPrivateLan(String ip) {
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }
}
