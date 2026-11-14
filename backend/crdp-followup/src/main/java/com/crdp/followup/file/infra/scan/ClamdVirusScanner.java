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
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            OutputStream out = socket.getOutputStream();
            // 命令自带 NUL 终止（"INSTREAM\0"），随后直接进分块流
            out.write("INSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < body.length; offset += CHUNK_SIZE) {
                int length = Math.min(CHUNK_SIZE, body.length - offset);
                writeChunk(out, body, offset, length);
            }
            out.write(new byte[4]); // 0 长度块 = 流结束
            out.flush();
            String reply = readReply(socket.getInputStream());
            log.debug(
                    "clamd 扫描完成 bytes={} reply={}",
                    body.length,
                    reply.strip().replaceAll("\0", ""));
            if (reply.endsWith("OK")) {
                return ScanResult.clean();
            }
            if (reply.endsWith("FOUND")) {
                // " stream: {病毒名} FOUND" → 取中间名称段
                String name =
                        reply.replace("stream:", "")
                                .replace("FOUND", "")
                                .strip()
                                .replaceAll("\0", "");
                return ScanResult.infected(name.isBlank() ? "UNKNOWN" : name);
            }
            throw unavailable("clamd 应答异常：" + reply.strip().replaceAll("\0", ""));
        } catch (IOException | RuntimeException failure) {
            // 连接拒绝 / 超时 / 应答损坏：一律不可用（fail closed），不降级放行
            throw unavailable(
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage());
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

    /** 读到连接关闭或换行为止（clamd 应答单行，部分实现不带换行直接关流）。 */
    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            buffer.write(b);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static ApiException unavailable(String reason) {
        log.warn("病毒扫描服务不可用（fail closed，上传不放行）：{}", reason);
        return new ApiException(503, 50300, "病毒扫描服务暂不可用，请稍后重试");
    }
}
