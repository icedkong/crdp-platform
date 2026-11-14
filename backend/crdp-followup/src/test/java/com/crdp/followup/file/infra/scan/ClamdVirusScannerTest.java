package com.crdp.followup.file.infra.scan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * E22-T15 / D-01 整改：clamd INSTREAM 协议适配器单元测试——用本地假 clamd（ServerSocket 按协议应答）锁定： 命令帧与分块编码（4 字节大端长度 +
 * 数据、0 长度结束）、干净 / 检出应答解析、 连接拒绝与应答异常一律 50300 fail closed（不放行）。
 */
class ClamdVirusScannerTest {

    private final FakeClamd clamd = new FakeClamd();

    @AfterEach
    void stop() {
        clamd.stop();
    }

    /** 干净应答 " stream: OK"：clean；请求侧按协议重组出的内容与发送体一致（协议正确性）。 */
    @Test
    void cleanReplyAndProtocolFraming() throws Exception {
        clamd.start(" stream: OK");
        byte[] body = new byte[5000]; // 大于单块 2048：验证多块重组
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 251);
        }
        VirusScanner.ScanResult result = scanner().scan(body);

        assertFalse(result.infected());
        clamd.awaitRequest();
        assertEquals("INSTREAM", clamd.command());
        assertArrayEquals(body, clamd.reassembled());
    }

    /** 检出应答 " stream: {病毒名} FOUND"：infected 且病毒名完整。 */
    @Test
    void foundReplyParsesVirusName() throws Exception {
        clamd.start(" stream: Win.Test.EICAR.HDB-1 FOUND");
        VirusScanner.ScanResult result =
                scanner().scan("eicar-test（示例）".getBytes(StandardCharsets.UTF_8));

        assertTrue(result.infected());
        assertEquals("Win.Test.EICAR.HDB-1", result.virusName());
    }

    /** 连接拒绝（无监听端口）：50300，不放行（fail closed）。 */
    @Test
    void connectionRefusedFailsClosedWith50300() {
        int freePort = unusedPort();
        ClamdVirusScanner scanner = new ClamdVirusScanner("127.0.0.1", freePort, 500, 500);
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> scanner.scan("x（示例）".getBytes(StandardCharsets.UTF_8)));
        assertEquals(50300, ex.code());
        assertTrue(ex.getMessage().contains("稍后重试"), ex.getMessage());
    }

    /** 应答异常（ERROR）：同样 50300 fail closed，不猜结论。 */
    @Test
    void errorReplyFailsClosedWith50300() throws Exception {
        clamd.start(" stream: clamd scanner ERROR");
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> scanner().scan("y（示例）".getBytes(StandardCharsets.UTF_8)));
        assertEquals(50300, ex.code());
    }

    private ClamdVirusScanner scanner() {
        return new ClamdVirusScanner("127.0.0.1", clamd.port(), 2000, 5000);
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 假 clamd：接受一个连接，解析 INSTREAM 帧，按构造应答回写一行后关流。 */
    private static final class FakeClamd {

        private ServerSocket server;
        private Thread thread;
        private final AtomicReference<String> command = new AtomicReference<>();
        private final AtomicReference<byte[]> reassembled = new AtomicReference<>(new byte[0]);
        private final CountDownLatch done = new CountDownLatch(1);

        void start(String reply) {
            try {
                server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                final String response = reply;
                thread = new Thread(() -> serve(response));
                thread.setDaemon(true);
                thread.start();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private void serve(String reply) {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                // 读命令（到 NUL）
                ByteArrayOutputStream cmd = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != 0 && b != -1) {
                    cmd.write(b);
                }
                command.set(cmd.toString(StandardCharsets.US_ASCII));
                // 读分块：4 字节大端长度；0 长度 = 结束
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                for (int length = in.readInt(); length > 0; length = in.readInt()) {
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    body.write(chunk);
                }
                reassembled.set(body.toByteArray());
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // 客户端先关连接等场景：测试用例自行断言结果
            } finally {
                done.countDown();
            }
        }

        void awaitRequest() throws InterruptedException {
            assertTrue(done.await(5, TimeUnit.SECONDS), "假 clamd 未收到完整请求");
        }

        String command() {
            return command.get();
        }

        byte[] reassembled() {
            return reassembled.get();
        }

        int port() {
            return server.getLocalPort();
        }

        void stop() {
            if (server != null && !server.isClosed()) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // 清理
                }
            }
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
