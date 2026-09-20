package dev.jsinco.brewery.bukkit.util.color;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ResourcePackSourceTest {

    private HttpServer server;
    private ExecutorService executor;
    private byte[] resourcePack;

    @BeforeEach
    void setUp() throws Exception {
        resourcePack = resourcePack();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/pack.zip", exchange -> {
            exchange.sendResponseHeaders(200, resourcePack.length);
            exchange.getResponseBody().write(resourcePack);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/pack.zip");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/invalid", exchange -> {
            byte[] invalid = "not a zip".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, invalid.length);
            exchange.getResponseBody().write(invalid);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void sha256FlagNeverReplacesZipWithDigest() {
        ResourcePackSource source = new ResourcePackSource.HttpResourcePackSource(url("/pack.zip"), true, null);

        assertDoesNotThrow(source::readPack);
    }

    @Test
    void followsRedirects() {
        ResourcePackSource source = new ResourcePackSource.HttpResourcePackSource(url("/redirect"), false, null);

        assertDoesNotThrow(source::readPack);
    }

    @Test
    void lenientReaderDoesNotCrashOnInvalidZip() {
        ResourcePackSource source = new ResourcePackSource.HttpResourcePackSource(url("/invalid"), false, null);

        assertDoesNotThrow(source::readPack);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static byte[] resourcePack() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":75,\"description\":\"RayCraft test\"}}"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
