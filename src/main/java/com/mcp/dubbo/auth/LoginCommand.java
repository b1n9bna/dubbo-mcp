package com.mcp.dubbo.auth;

import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.DubboAdminDriver;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Credential input never goes through command-line arguments or log output. */
public final class LoginCommand {
    public static void run(String[] args) throws Exception {
        String env = "prod", file = null;
        boolean clipboard = false, stdin = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--env=")) env = a.substring(6);
            else if ("--env".equals(a) && i + 1 < args.length) env = args[++i];
            else if ("--clipboard".equals(a)) clipboard = true;
            else if ("--stdin".equals(a)) stdin = true;
            else if ("--file".equals(a) && i + 1 < args.length) file = args[++i];
            else if (!"login".equals(a) && !"--login".equals(a)) throw new IllegalArgumentException("Unknown login option");
        }
        ConfigManager config = ConfigManager.getInstance();
        String type = config.getProperty("env." + env + ".type");
        if (type == null && config.getProperty("env." + env + ".admin_url") != null) {
            type = "dubbo-admin";
        }
        if (!"dubbo-admin".equalsIgnoreCase(type))
            throw new IllegalArgumentException("Login requires a configured dubbo-admin environment: " + env);
        if ((clipboard ? 1 : 0) + (stdin ? 1 : 0) + (file != null ? 1 : 0) > 1)
            throw new IllegalArgumentException("Choose only one of --clipboard, --stdin, --file");
        System.err.println("🔐 Dubbo Admin credentials (" + env + ")");
        System.err.println("在已登录的 Dubbo Admin 控制台执行：");
        System.err.println("copy(JSON.stringify({cookie:document.cookie,authorization:localStorage.getItem('token')||localStorage.getItem('Admin-Token')}))");
        String value;
        if (clipboard) {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"))
                throw new IllegalArgumentException("--clipboard currently supports macOS; use --file or --stdin");
            Process process = new ProcessBuilder("/usr/bin/pbpaste").start();
            // Clipboard content is bounded; read concurrently so a full pipe cannot deadlock waitFor.
            java.util.concurrent.FutureTask<String> read = new java.util.concurrent.FutureTask<>(() -> readInput(process.getInputStream(), false));
            Thread reader = new Thread(read, "clipboard-reader"); reader.setDaemon(true); reader.start();
            try {
                value = read.get(5, TimeUnit.SECONDS);
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("Clipboard unavailable");
            } finally { process.destroyForcibly(); }
        } else if (file != null) {
            try (InputStream input = Files.newInputStream(Paths.get(file))) { value = readInput(input, false); }
        } else if (stdin || System.console() == null) {
            System.err.println("Reading credentials from stdin (finish with EOF)...");
            value = readInput(System.in, false);
        } else {
            System.err.print("粘贴后按回车（输入不回显；macOS 也可使用 login --clipboard）：");
            value = readTerminal();
        }
        value = AuthSessionManager.normalizeCredential(value);
        System.err.println("正在验证登录态...");
        new DubboAdminDriver(env).verifyCredential(value);
        AuthSessionManager.getInstance().saveCredential(env, value);
        System.err.println("✅ 登录验证通过，凭据已保存；运行中的新版 MCP 将在下次请求自动读取。");
    }

    static String readInput(InputStream input, boolean oneLine) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (oneLine && (b == '\n' || b == '\r')) break;
            if (oneLine && (b == 3 || b == 4)) throw new IOException("Login cancelled");
            if (oneLine && (b == 127 || b == 8)) {
                byte[] current = bytes.toByteArray(); bytes.reset();
                if (current.length > 0) bytes.write(current, 0, current.length - 1);
            } else bytes.write(b);
            if (bytes.size() > 65536) throw new IOException("Credential exceeds 64 KiB");
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static String readTerminal() throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            char[] chars = System.console().readPassword();
            if (chars == null) throw new IOException("Login cancelled");
            try { return new String(chars); } finally { Arrays.fill(chars, '\0'); }
        }
        String state = stty("-g").trim();
        Thread restore = new Thread(() -> { try { stty(state); } catch (Exception ignored) { } });
        Runtime.getRuntime().addShutdownHook(restore);
        try {
            // Darwin's canonical line buffer drops long pasted cookies and their newline.
            stty("-icanon", "-echo", "min", "1", "time", "0");
            return readInput(System.in, true);
        } finally {
            stty(state);
            Runtime.getRuntime().removeShutdownHook(restore);
            System.err.println();
        }
    }
    private static String stty(String... args) throws Exception {
        List<String> command = new ArrayList<>(); command.add("stty"); command.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(command).redirectInput(new File("/dev/tty")).start();
        String result = readInput(p.getInputStream(), false);
        if (p.waitFor() != 0) throw new IOException("Cannot configure terminal; use login --clipboard or --stdin");
        return result;
    }
}
