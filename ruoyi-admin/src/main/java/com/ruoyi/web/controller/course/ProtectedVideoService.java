package com.ruoyi.web.controller.course;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.config.RuoYiConfig;

/**
 * Converts course videos into a private AES-128 HLS cache. The cache lives next
 * to the public upload directory, never below /profile, and is only exposed by
 * the token-checked playlist/segment/key controller endpoints.
 */
@Component
public class ProtectedVideoService
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PREPARING = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();
    private static final ExecutorService CONVERTER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "course-protected-video-converter");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${ruoyi.profile:}")
    private String profilePath;

    public boolean isReady(String source)
    {
        try
        {
            File directory = cacheDirectory(source);
            return new File(directory, "index.m3u8").isFile()
                && new File(directory, "key.bin").isFile()
                && segmentFiles(directory).length > 0;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    public boolean isPreparing(String source)
    {
        try
        {
            return PREPARING.contains(cacheKey(source));
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    public String failure(String source)
    {
        try
        {
            return FAILURES.getOrDefault(cacheKey(source), "");
        }
        catch (Exception ignored)
        {
            return "视频源无效";
        }
    }

    public void prepareAsync(String source)
    {
        String value = normalizeSource(source);
        if (value.length() == 0 || isReady(value)) return;
        try
        {
            String key = cacheKey(value);
            if (!PREPARING.add(key)) return;
            FAILURES.remove(key);
            CONVERTER.submit(() -> {
                try
                {
                    generate(value, key);
                    FAILURES.remove(key);
                }
                catch (Exception e)
                {
                    FAILURES.put(key, safeFailure(e));
                    System.err.println("Protected HLS conversion failed: " + safeFailure(e));
                }
                finally
                {
                    PREPARING.remove(key);
                }
            });
        }
        catch (Exception e)
        {
            System.err.println("Protected HLS scheduling failed: " + safeFailure(e));
        }
    }

    public void prepareSourcesAsync(Object value)
    {
        collectVideoSources(value, "").forEach(this::prepareAsync);
    }

    public String protectedPlaylist(String source, String token) throws IOException
    {
        File playlist = new File(cacheDirectory(source), "index.m3u8");
        if (!playlist.isFile()) throw new IOException("安全视频清单尚未生成");
        String encodedToken = encode(token);
        String keyUrl = "/course/app/lesson/key.bin?token=" + encodedToken;
        StringBuilder result = new StringBuilder();
        for (String rawLine : Files.readAllLines(playlist.toPath(), StandardCharsets.UTF_8))
        {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-ALLOW-CACHE:"))
            {
                result.append("#EXT-X-ALLOW-CACHE:NO\n");
            }
            else if (line.startsWith("#EXT-X-KEY:"))
            {
                result.append(line.replaceAll("URI=\"[^\"]+\"", "URI=\"" + Matcher.quoteReplacement(keyUrl) + "\""));
                result.append('\n');
            }
            else if (line.length() > 0 && !line.startsWith("#"))
            {
                String name = new File(line.replace('\\', '/')).getName();
                result.append("/course/app/lesson/segment.ts?token=")
                    .append(encodedToken)
                    .append("&name=")
                    .append(encode(name))
                    .append('\n');
            }
            else
            {
                result.append(rawLine).append('\n');
            }
        }
        return result.toString();
    }

    public File segment(String source, String name) throws IOException
    {
        String safeName = name == null ? "" : new File(name.replace('\\', '/')).getName();
        if (!safeName.matches("^seg_[0-9]{5,8}\\.ts$")) return null;
        File directory = cacheDirectory(source).getCanonicalFile();
        File target = new File(directory, safeName).getCanonicalFile();
        String prefix = directory.getPath().endsWith(File.separator) ? directory.getPath() : directory.getPath() + File.separator;
        return target.getPath().startsWith(prefix) && target.isFile() ? target : null;
    }

    public File keyFile(String source) throws IOException
    {
        File key = new File(cacheDirectory(source), "key.bin").getCanonicalFile();
        return key.isFile() && key.length() == 16L ? key : null;
    }

    private void generate(String source, String key) throws Exception
    {
        File target = cacheDirectory(source);
        if (isReady(source)) return;
        File root = protectedRoot();
        File temp = new File(root, ".building-" + key + "-" + UUID.randomUUID().toString().replace("-", ""));
        deleteRecursively(temp);
        if (!temp.mkdirs()) throw new IOException("无法创建安全视频临时目录");
        try
        {
            String input = ffmpegInput(source);
            writeKeyFiles(temp);
            boolean converted = runFfmpeg(input, temp, true);
            if (!converted)
            {
                deleteRecursively(temp);
                if (!temp.mkdirs()) throw new IOException("无法重建安全视频临时目录");
                writeKeyFiles(temp);
                converted = runFfmpeg(input, temp, false);
            }
            File playlist = new File(temp, "index.m3u8");
            if (!converted || !playlist.isFile() || segmentFiles(temp).length == 0)
            {
                throw new IOException("视频无法转换为安全分段格式");
            }
            Files.deleteIfExists(new File(temp, "key-info.txt").toPath());
            Files.deleteIfExists(new File(temp, "ffmpeg.log").toPath());
            if (target.exists()) deleteRecursively(target);
            try
            {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            catch (Exception ignored)
            {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            if (temp.exists()) deleteRecursively(temp);
        }
    }

    private boolean runFfmpeg(String input, File output, boolean copyCodecs) throws Exception
    {
        List<String> command = new ArrayList<>(Arrays.asList(
            "ffmpeg", "-y", "-nostdin", "-loglevel", "error",
            "-i", input,
            "-map", "0:v:0?", "-map", "0:a:0?"
        ));
        if (copyCodecs)
        {
            command.addAll(Arrays.asList("-c", "copy"));
        }
        else
        {
            command.addAll(Arrays.asList(
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*8)"
            ));
        }
        command.addAll(Arrays.asList(
            "-f", "hls",
            "-hls_time", "8",
            "-hls_list_size", "0",
            "-hls_playlist_type", "vod",
            "-hls_flags", "independent_segments",
            "-hls_key_info_file", new File(output, "key-info.txt").getAbsolutePath(),
            "-hls_segment_filename", new File(output, "seg_%05d.ts").getAbsolutePath(),
            new File(output, "index.m3u8").getAbsolutePath()
        ));
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(new File(output, "ffmpeg.log"))
            .start();
        boolean finished = process.waitFor(copyCodecs ? 10L : 30L, TimeUnit.MINUTES);
        if (!finished)
        {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private void writeKeyFiles(File output) throws IOException
    {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        RANDOM.nextBytes(key);
        RANDOM.nextBytes(iv);
        File keyFile = new File(output, "key.bin");
        Files.write(keyFile.toPath(), key);
        List<String> info = Arrays.asList(
            "key.bin",
            keyFile.getAbsolutePath(),
            hex(iv)
        );
        Files.write(new File(output, "key-info.txt").toPath(), info, StandardCharsets.UTF_8);
    }

    private String ffmpegInput(String source) throws IOException
    {
        File local = resolveLocalVideoFile(source);
        if (local != null) return local.getCanonicalPath();
        String value = normalizeSource(source);
        if (value.matches("^https?://.+")) return value;
        throw new IOException("视频源文件不存在");
    }

    private File cacheDirectory(String source) throws IOException
    {
        return new File(protectedRoot(), cacheKey(source)).getCanonicalFile();
    }

    private File protectedRoot() throws IOException
    {
        File profile = new File(firstNonBlank(profilePath, RuoYiConfig.getProfile())).getCanonicalFile();
        File parent = profile.getParentFile() == null ? profile : profile.getParentFile();
        File root = new File(parent, "protected-video").getCanonicalFile();
        if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建安全视频目录");
        return root;
    }

    private String cacheKey(String source) throws IOException
    {
        String value = normalizeSource(source);
        File local = resolveLocalVideoFile(value);
        String identity = local == null
            ? value
            : local.getCanonicalPath() + ":" + local.length() + ":" + local.lastModified();
        try
        {
            return hex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        }
        catch (Exception e)
        {
            throw new IOException("无法生成安全视频标识", e);
        }
    }

    private File resolveLocalVideoFile(String source) throws IOException
    {
        String requested = normalizeSource(source).replace('\\', '/');
        if (requested.matches("^https?://.+"))
        {
            try
            {
                requested = new URL(requested).getPath();
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        requested = URLDecoder.decode(requested, StandardCharsets.UTF_8.name()).replaceFirst("^/prod-api", "");
        if (!requested.matches("^/(profile|avatar|upload|uploads)/.+\\.(mp4|webm|mov|m4v)(?:$|[?#].*)")) return null;
        int queryIndex = requested.indexOf('?');
        if (queryIndex >= 0) requested = requested.substring(0, queryIndex);
        String relative = requested.startsWith("/profile/") ? requested.substring("/profile/".length()) : requested.substring(1);
        File root = new File(firstNonBlank(profilePath, RuoYiConfig.getProfile())).getCanonicalFile();
        File target = new File(root, relative).getCanonicalFile();
        String prefix = root.getPath().endsWith(File.separator) ? root.getPath() : root.getPath() + File.separator;
        return target.getPath().startsWith(prefix) && target.isFile() ? target : null;
    }

    private List<String> collectVideoSources(Object value, String parentKey)
    {
        if (value == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        if (value instanceof Map)
        {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (key.toLowerCase(Locale.ROOT).contains("videourl") && child instanceof String)
                {
                    String source = normalizeSource((String) child);
                    if (source.length() > 0) result.add(source);
                }
                result.addAll(collectVideoSources(child, key));
            }
        }
        else if (value instanceof Iterable)
        {
            for (Object child : (Iterable<?>) value) result.addAll(collectVideoSources(child, parentKey));
        }
        return result;
    }

    private static File[] segmentFiles(File directory)
    {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().matches("^seg_[0-9]{5,8}\\.ts$"));
        return files == null ? new File[0] : files;
    }

    private static void deleteRecursively(File target) throws IOException
    {
        if (target == null || !target.exists()) return;
        if (target.isDirectory())
        {
            File[] children = target.listFiles();
            if (children != null)
            {
                for (File child : children) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(target.toPath());
    }

    private static String safeFailure(Exception error)
    {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static String normalizeSource(String source)
    {
        return source == null ? "" : source.trim();
    }

    private static String firstNonBlank(String first, String second)
    {
        return first != null && first.trim().length() > 0 ? first.trim() : (second == null ? "" : second.trim());
    }

    private static String encode(String value)
    {
        try
        {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        }
        catch (Exception ignored)
        {
            return "";
        }
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
