package org.mark.llamacpp.server.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;

public class CertController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(CertController.class);

    private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
    private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
    private static final String I18N_CERT_STATUS_FAILED = "api.error.cert.status.failed";
    private static final String I18N_CERT_FILE_NOTFOUND = "api.error.cert.file.notfound";
    private static final String I18N_CERT_DOWNLOAD_FAILED = "api.error.cert.download.failed";
    private static final String I18N_CERT_PASSWORD_MINLENGTH = "api.error.cert.password.minlength";
    private static final String I18N_CERT_GENERATE_FAILED = "api.error.cert.generate.failed";

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (uri.equals("/api/cert/generate")) {
            this.handleGenerate(ctx, request);
            return true;
        }
        if (uri.equals("/api/cert/status")) {
            this.handleStatus(ctx, request);
            return true;
        }
        if (uri.equals("/api/cert/download")) {
            this.handleDownload(ctx, request);
            return true;
        }
        return false;
    }

    private void handleStatus(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
            String keystorePath = LlamaServer.getHttpsCertPath();
            Path path = Paths.get(keystorePath).toAbsolutePath().normalize();
            boolean exists = Files.exists(path) && Files.isRegularFile(path);
            Path caCertPath = Paths.get("ssl", "ca-cert.cer").toAbsolutePath().normalize();
            boolean caExists = Files.exists(caCertPath) && Files.isRegularFile(caCertPath);

            Map<String, Object> data = new HashMap<>();
            data.put("exists", exists);
            data.put("path", keystorePath);
            data.put("passwordConfigured", LlamaServer.getHttpsPassword() != null && !LlamaServer.getHttpsPassword().isBlank());
            data.put("caCertPath", caCertPath.toString());
            data.put("caCertExists", caExists);
            if (exists) {
                try {
                    data.put("size", Files.size(path));
                } catch (Exception ignore) {
                }
            }
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (RequestMethodException e) {
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("获取证书状态时发生异常", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CERT_STATUS_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.GET) {
            LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_METHOD_GET_ONLY);
            return;
        }
        try {
            String query = request.uri();
            int qIdx = query.indexOf('?');
            String type = "";
            if (qIdx >= 0) {
                String[] pairs = query.substring(qIdx + 1).split("&");
                for (String pair : pairs) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "type".equals(pair.substring(0, eq))) {
                        type = pair.substring(eq + 1);
                        break;
                    }
                }
            }

            Path filePath;
            String contentType;
            String fileName;
            if ("ca".equalsIgnoreCase(type)) {
                filePath = Paths.get("ssl", "ca-cert.cer").toAbsolutePath().normalize();
                contentType = "application/x-x509-ca-cert";
                fileName = "ca-cert.cer";
            } else {
                String keystorePath = LlamaServer.getHttpsCertPath();
                filePath = Paths.get(keystorePath).toAbsolutePath().normalize();
                contentType = "application/x-pkcs12";
                fileName = filePath.getFileName() == null ? "keystore.p12" : filePath.getFileName().toString();
            }

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_CERT_FILE_NOTFOUND);
                return;
            }

            long fileLength = Files.size(filePath);
            RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");

            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            LlamaServer.setCorsHeaders(response.headers());

            ctx.write(response);
            ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
            ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            last.addListener(f -> {
                try {
                    raf.close();
                } catch (Exception ignore) {
                }
                ctx.close();
            });
        } catch (Exception e) {
            logger.error("下载证书时发生异常", e);
            LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_CERT_DOWNLOAD_FAILED + ": " + e.getMessage());
        }
    }

    private void handleGenerate(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
            JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
            if (body == null)
                return;

            List<String> ips = JsonUtil.getJsonStringList(body.get("ips"));
            List<String> hostnames = JsonUtil.getJsonStringList(body.get("hostnames"));

            // 参数校验：只允许合法的域名和 IP 格式
            if (hostnames != null) hostnames.removeIf(h -> h == null || !h.trim().matches("^[a-zA-Z0-9.\\-]+$"));
            if (ips != null) ips.removeIf(ip -> ip == null || !ip.trim().matches("^[0-9a-fA-F.:]+$"));

            int validity = JsonUtil.getJsonInt(body, "validity", 3650);
            if (validity < 1)
                validity = 3650;
            String password = JsonUtil.getJsonString(body, "password");
            if (password.isEmpty()) {
                password = generatePassword();
            }
            if (password.length() < 6) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CERT_PASSWORD_MINLENGTH));
                return;
            }
            int keysize = JsonUtil.getJsonInt(body, "keysize", 2048);
            if (keysize != 2048 && keysize != 4096)
                keysize = 2048;
            String outputDir = "ssl";

            String userCn = JsonUtil.getJsonString(body, "cn");
            if (userCn.isEmpty()) {
                if (hostnames != null && !hostnames.isEmpty()) {
                    userCn = hostnames.get(0);
                } else {
                    userCn = "localhost";
                }
            }

            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);
            Path keystoreFile = outputPath.resolve("keystore.p12");
            Path caCertFile = outputPath.resolve("ca-cert.cer");
            Path caKeystore = outputPath.resolve("ca-keystore-temp.p12");
            Path serverCsr = outputPath.resolve("server.csr");
            Path serverSigned = outputPath.resolve("server-signed.cer");
            Path caCertTemp = outputPath.resolve("ca-cert-temp.cer");

            // 清理旧文件
            try {
                Files.deleteIfExists(keystoreFile);
                Files.deleteIfExists(caCertFile);
                Files.deleteIfExists(caKeystore);
                Files.deleteIfExists(serverCsr);
                Files.deleteIfExists(serverSigned);
                Files.deleteIfExists(caCertTemp);
            } catch (IOException e) {
                logger.warn("清理旧证书文件失败", e);
            }

            String cn = userCn;
            String caDname = "CN=" + cn + "-CA,OU=llamacpp-hub,O=llamacpp-hub,L=Unknown,ST=Unknown,C=CN";
            String serverDname = "CN=" + cn + ",OU=llamacpp-hub,O=llamacpp-hub,L=Unknown,ST=Unknown,C=CN";

            String javaHome = System.getProperty("java.home");
            boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
            String keytoolPath = javaHome + File.separator + "bin" + File.separator
                    + (isWindows ? "keytool.exe" : "keytool");

            StringBuilder sanBuilder = new StringBuilder();
            if (hostnames != null) {
                for (String h : hostnames) {
                    String t = h.trim();
                    if (!t.isEmpty()) {
                        if (sanBuilder.length() > 0)
                            sanBuilder.append(",");
                        sanBuilder.append("DNS:").append(t);
                    }
                }
            }
            if (!sanBuilder.toString().contains("DNS:localhost")) {
                if (sanBuilder.length() > 0)
                    sanBuilder.append(",");
                sanBuilder.append("DNS:localhost");
            }
            if (!sanBuilder.toString().contains("IP:127.0.0.1")) {
                sanBuilder.append(",IP:127.0.0.1");
            }
            if (ips != null) {
                for (String ip : ips) {
                    String t = ip.trim();
                    if (!t.isEmpty() && !"127.0.0.1".equals(t)) {
                        sanBuilder.append(",IP:").append(t);
                    }
                }
            }

            try {
                // 1. 生成 CA 根证书（含 BasicConstraints CA:true 与 keyCertSign）
                runKeytool(keytoolPath,
                        "-genkeypair",
                        "-alias", "ca",
                        "-keyalg", "RSA",
                        "-keysize", String.valueOf(keysize),
                        "-keystore", caKeystore.toString(),
                        "-storetype", "PKCS12",
                        "-storepass", password,
                        "-keypass", password,
                        "-dname", caDname,
                        "-validity", String.valueOf(validity),
                        "-ext", "bc:critical=ca:true,pathlen:1",
                        "-ext", "ku:critical=keyCertSign,cRLSign");

                // 2. 生成服务器密钥库（自签占位证书，后续会被替换）
                runKeytool(keytoolPath,
                        "-genkeypair",
                        "-alias", "server",
                        "-keyalg", "RSA",
                        "-keysize", String.valueOf(keysize),
                        "-keystore", keystoreFile.toString(),
                        "-storetype", "PKCS12",
                        "-storepass", password,
                        "-keypass", password,
                        "-dname", serverDname,
                        "-validity", String.valueOf(validity));

                // 3. 为服务器生成证书签名请求 CSR
                runKeytool(keytoolPath,
                        "-certreq",
                        "-alias", "server",
                        "-keystore", keystoreFile.toString(),
                        "-storepass", password,
                        "-keypass", password,
                        "-file", serverCsr.toString());

                // 4. 使用 CA 签发服务器证书（含 SAN、serverAuth EKU）
                runKeytool(keytoolPath,
                        "-gencert",
                        "-infile", serverCsr.toString(),
                        "-outfile", serverSigned.toString(),
                        "-alias", "ca",
                        "-keystore", caKeystore.toString(),
                        "-storepass", password,
                        "-keypass", password,
                        "-validity", String.valueOf(validity),
                        "-ext", "SAN=" + sanBuilder.toString(),
                        "-ext", "ku=digitalSignature,keyEncipherment",
                        "-ext", "eku=serverAuth",
                        "-rfc");

                // 5. 导出 CA 公钥证书，供客户端安装到受信任根证书颁发机构
                runKeytool(keytoolPath,
                        "-exportcert",
                        "-alias", "ca",
                        "-keystore", caKeystore.toString(),
                        "-storepass", password,
                        "-file", caCertTemp.toString(),
                        "-rfc");

                // 6. 构建服务器证书链并导入密钥库，替换原有自签占位证书
                String chainContent = Files.readString(caCertTemp, StandardCharsets.UTF_8);
                String serverCertContent = Files.readString(serverSigned, StandardCharsets.UTF_8);
                String fullChain = serverCertContent + System.lineSeparator() + chainContent;
                Path serverChain = outputPath.resolve("server-chain.cer");
                Files.writeString(serverChain, fullChain, StandardCharsets.UTF_8);

                runKeytool(keytoolPath,
                        "-importcert",
                        "-alias", "server",
                        "-file", serverChain.toString(),
                        "-keystore", keystoreFile.toString(),
                        "-storepass", password,
                        "-keypass", password,
                        "-noprompt");

                // 7. 保存 CA 证书到固定位置，方便浏览器/客户端下载安装
                Files.copy(caCertTemp, caCertFile);

                // 清理临时文件
                Files.deleteIfExists(caKeystore);
                Files.deleteIfExists(serverCsr);
                Files.deleteIfExists(serverSigned);
                Files.deleteIfExists(caCertTemp);
                Files.deleteIfExists(serverChain);
            } catch (Exception e) {
                logger.error("证书生成失败", e);
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CERT_GENERATE_FAILED + ": " + e.getMessage()));
                return;
            }

            String expireDate = LocalDate.now().plusDays(validity).format(DateTimeFormatter.ISO_LOCAL_DATE);

            Map<String, Object> data = new HashMap<>();
            data.put("path", keystoreFile.toString());
            data.put("caCertPath", caCertFile.toString());
            data.put("password", password);
            data.put("san", sanBuilder.toString());
            data.put("expireDate", expireDate);
            data.put("keysize", keysize);

            LlamaServer.updateHttpsConfig(null, keystoreFile.toString(), password);
            logger.info("HTTPS证书生成成功: {}, CA证书: {}", keystoreFile, caCertFile);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (RequestMethodException e) {
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("证书生成异常", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CERT_GENERATE_FAILED + ": " + e.getMessage()));
        }
    }

    /**
     * 执行 keytool 命令，失败时抛出异常并附带命令输出。
     */
    private String runKeytool(String keytoolPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(keytoolPath);
        for (String arg : args) {
            cmd.add(arg);
        }

        String cmdString = cmd.stream().map(s -> {
            if (s.contains(" ") || s.contains(",") || s.contains("=") || s.contains(";"))
                return "\"" + s + "\"";
            return s;
        }).collect(Collectors.joining(" "));

        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows && cmd.get(0).contains(" ")) {
            cmdString = "& " + cmdString;
        }
        logger.info("执行命令: {}", cmdString);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String err = output.toString().trim();
            logger.error("keytool 执行失败, exitCode={}, 输出: {}", exitCode, err);
            throw new RuntimeException("keytool 执行失败 (exitCode=" + exitCode + "): " + err);
        }
        return output.toString();
    }

    private static String generatePassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
