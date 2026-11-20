package com.crdp.followup.file.infra.scan;

import com.crdp.followup.file.infra.scan.VirusScanner.ScanResult;
import com.crdp.framework.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * clamd INSTREAM 同步扫描适配器（E22-T15 / D-01 整改，tech-plan §10 方案 A）。
 *
 * <p>协议（clamav 官方 clamd）：连接后发 {@code INSTREAM\0}，随后按「4 字节大端长度 + 数据」分块发文件体， 以 0 长度块结束；应答一行：{@code "
 * stream: OK"}（干净）或 {@code " stream: {病毒名} FOUND"}（检出）， {@code " stream: {名称} ERROR"} 按不可用处理。分块取
 * 2048 字节（clamd 示例口径，避免超长单块）。
 *
 * <p>每次扫描独立短连接（无连接池）：上传是低频操作，短连接天然规避 clamd 重启后的半开连接；连接 / 读超时由配置 控制，超时即 50300 fail closed，不放行。
 */
public class ClamdVirusScanner implements VirusScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamdVirusScanner.class);

    /** INSTREAM 分块大小（字节）。 */
    private static final int CHUNK_SIZE = 2048;

    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
        var executor =
                new java.util.concurrent.ScheduledThreadPoolExecutor(
                        1,
                        task -> {
                            var thread = new Thread(task, "clamd-deadline");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public ClamdVirusScanner(VirusScanProperties properties) {
        this(
                properties.getClamd().getHost(),
                properties.getClamd().getPort(),
                (int) properties.getClamd().getConnectTimeout().toMillis(),
                (int) properties.getClamd().getReadTimeout().toMillis());
    }

    /** 测试 / 显式装配。 */
    public ClamdVirusScanner(
            String host, int port, int connectTimeoutMillis, int readTimeoutMillis) {
        if (host == null
                || host.isBlank()
                || port < 1
                || port > 65535
                || connectTimeoutMillis < 1
                || connectTimeoutMillis > 60000
                || readTimeoutMillis < 1
                || readTimeoutMillis > 60000) throw new IllegalArgumentException("扫描地址或有限超时配置无效");
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ScanResult scan(byte[] body) {
        try (Socket socket = new Socket()) {
            var deadline =
                    DEADLINES.schedule(
                            () -> {
                                try {
                                    socket.close();
                                } catch (IOException ignored) {
                                    /* 仅关闭本次扫描连接 */
                                }
                            },
                            Math.min(60000L, (long) connectTimeoutMillis + readTimeoutMillis),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
                socket.setSoTimeout(readTimeoutMillis);
                OutputStream out = socket.getOutputStream();
                // 命令自带 NUL 终止（"zINSTREAM\0"），随后直接进分块流
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                for (int offset = 0; offset < body.length; offset += CHUNK_SIZE) {
                    int length = Math.min(CHUNK_SIZE, body.length - offset);
                    writeChunk(out, body, offset, length);
                }
                out.write(new byte[4]); // 0 长度块 = 流结束
                out.flush();
                String reply = readReply(socket.getInputStream());
                if ("stream: OK".equals(reply.strip())) return ScanResult.clean();
                if (reply.strip().matches("stream: [A-Za-z0-9_.:()/-]{1,200} FOUND")) {
                    String name = reply.strip().substring("stream: ".length());
                    return ScanResult.infected(
                            name.substring(0, name.length() - " FOUND".length()));
                }
                throw unavailable("应答不合法");
            } finally {
                deadline.cancel(false);
            }
        } catch (IOException | RuntimeException failure) {
            // 连接拒绝 / 超时 / 应答损坏：一律不可用（fail closed），不降级放行
            throw unavailable("连接、超时或协议错误");
        }
    }

    private static void writeChunk(OutputStream out, byte[] body, int offset, int length)
            throws IOException {
        out.write((length >>> 24) & 0xFF);
        out.write((length >>> 16) & 0xFF);
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(body, offset, length);
    }

    /** 只接受完整 NUL / 换行终止的有界应答，不将截断或任意 OK 后缀当作干净。 */
    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0 || b == '\n') return buffer.toString(StandardCharsets.UTF_8);
            if (buffer.size() >= 4096) throw new IOException("应答过长");
            buffer.write(b);
        }
        throw new IOException("应答未完整终止");
    }

    private static ApiException unavailable(String reason) {
        log.warn("病毒扫描服务不可用（fail closed，上传不放行）：{}", reason);
        return new ApiException(503, 50300, "病毒扫描服务暂不可用，请稍后重试");
    }
}
