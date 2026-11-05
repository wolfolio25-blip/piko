/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * Converts X's animated GIF video assets (tweet_video/*.mp4) to actual GIF89a
 * animations. No FFmpeg dependency, no .mp4 files disguised as .gif.
 */
package app.morphe.extension.twitter.patches.nativeFeatures.downloader;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class TrueGifDownloader {
    private static final int MAX_SIZE = 480;
    private static final int FRAME_MS = 100; // 10 fps; reduces very large GIF sizes.
    private static final long MAX_DURATION_MS = 60_000;
    private static final long MAX_BYTES = 120L * 1024 * 1024;

    private TrueGifDownloader() {}

    /** X hosts looping animated GIFs as MP4s under tweet_video/. */
    public static boolean isAnimatedGif(String url, String ext) {
        if (url == null || ext == null || !"mp4".equalsIgnoreCase(ext)) return false;
        try {
            Uri uri = Uri.parse(url);
            return "video.twimg.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/tweet_video/");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Never runs network or video decoding on the UI thread. */
    public static void download(Context context, String url, String fileName) {
        if (context == null || url == null) return;
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;
        new Thread(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("piko_true_gif_", ".mp4", target.getCacheDir());
                fetch(url, temp);
                String safeName = (fileName == null ? "twitter_gif" : fileName)
                        .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                if (safeName.length() > 100) safeName = safeName.substring(0, 100);
                if (safeName.isEmpty()) safeName = "twitter_gif";
                encodeAndSave(target, temp, safeName + ".gif");
                message(target, "Animated GIF saved: " + safeName + ".gif");
            } catch (Exception e) {
                message(target, "GIF download failed: " + e.getMessage());
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        }, "PikoTrueGif").start();
    }

    private static void message(Context ctx, String text) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> Toast.makeText(ctx, text, Toast.LENGTH_LONG).show());
    }

    private static void fetch(String url, File file) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            if (connection.getResponseCode() / 100 != 2)
                throw new IOException("Media download HTTP " + connection.getResponseCode());
            long size = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    size += n;
                    if (size > MAX_BYTES) throw new IOException("Animated GIF source is too large");
                    output.write(buffer, 0, n);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void encodeAndSave(Context context, File source, String name) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Uri outputUri = null;
        File legacyFile = null;
        boolean success = false;
        try {
            retriever.setDataSource(source.getAbsolutePath());
            String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = durationText == null ? 0 : Long.parseLong(durationText);
            if (duration <= 0 || duration > MAX_DURATION_MS)
                throw new IOException("GIF must be between 0 and 60 seconds");
            Bitmap first = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (first == null) throw new IOException("Could not decode the animation");
            int srcWidth = first.getWidth(), srcHeight = first.getHeight();
            first.recycle();
            float factor = Math.min(1f, MAX_SIZE / (float) Math.max(srcWidth, srcHeight));
            int width = Math.max(1, Math.round(srcWidth * factor));
            int height = Math.max(1, Math.round(srcHeight * factor));

            OutputStream target;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/gif");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Twitter");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                outputUri = context.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (outputUri == null) throw new IOException("Could not create GIF in Pictures");
                target = context.getContentResolver().openOutputStream(outputUri, "w");
                if (target == null) throw new IOException("Could not write GIF");
            } else {
                File directory = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "Twitter");
                if (!directory.exists() && !directory.mkdirs())
                    throw new IOException("Could not create Pictures/Twitter");
                legacyFile = new File(directory, name);
                int suffix = 1;
                while (legacyFile.exists()) {
                    legacyFile = new File(directory, name.substring(0, name.length() - 4)
                            + "_" + suffix++ + ".gif");
                }
                target = new FileOutputStream(legacyFile);
            }

            try (OutputStream out = new BufferedOutputStream(target)) {
                GifWriter gif = new GifWriter(out, width, height, FRAME_MS / 10);
                for (long time = 0; time < duration; time += FRAME_MS) {
                    Bitmap frame = retriever.getFrameAtTime(
                            time * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame == null) continue;
                    Bitmap sized = frame;
                    if (frame.getWidth() != width || frame.getHeight() != height)
                        sized = Bitmap.createScaledBitmap(frame, width, height, true);
                    gif.frame(sized);
                    if (sized != frame) sized.recycle();
                    frame.recycle();
                }
                gif.finish();
            }
            if (outputUri != null) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(outputUri, ready, null, null);
            } else if (legacyFile != null) {
                android.media.MediaScannerConnection.scanFile(context,
                        new String[]{legacyFile.getAbsolutePath()}, new String[]{"image/gif"}, null);
            }
            success = true;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
            if (!success) {
                if (outputUri != null) context.getContentResolver().delete(outputUri, null, null);
                if (legacyFile != null) legacyFile.delete();
            }
        }
    }

    /** GIF89a encoder with a fixed RGB332 palette and standards-compliant LZW. */
    private static final class GifWriter {
        private final OutputStream out;
        private final int width, height, delay;
        private boolean hasFrames;

        GifWriter(OutputStream out, int width, int height, int delayCs) throws IOException {
            this.out = out;
            this.width = width;
            this.height = height;
            delay = Math.max(2, delayCs);
            ascii("GIF89a");
            word(width);
            word(height);
            out.write(0xF7); // 256-color global color table
            out.write(0);
            out.write(0);
            for (int i = 0; i < 256; i++) {
                out.write(((i >> 5) & 7) * 255 / 7);
                out.write(((i >> 2) & 7) * 255 / 7);
                out.write((i & 3) * 255 / 3);
            }
            // Loop forever.
            out.write(new byte[]{0x21, (byte) 0xFF, 11});
            ascii("NETSCAPE2.0");
            out.write(new byte[]{3, 1, 0, 0, 0});
        }

        void frame(Bitmap bitmap) throws IOException {
            int[] colors = new int[width * height];
            bitmap.getPixels(colors, 0, width, 0, 0, width, height);
            byte[] pixels = new byte[colors.length];
            for (int i = 0; i < colors.length; i++) {
                int color = colors[i];
                pixels[i] = (byte) (((color >> 16) & 0xE0)
                        | ((color >> 11) & 0x1C) | ((color >> 6) & 3));
            }
            // Graphic Control Extension: frame delay, no transparency.
            out.write(new byte[]{0x21, (byte) 0xF9, 4, 0});
            word(delay);
            out.write(0);
            out.write(0);
            out.write(0x2C); // Image descriptor
            word(0);
            word(0);
            word(width);
            word(height);
            out.write(0);
            out.write(8); // minimum LZW code size
            byte[] encoded = lzw(pixels);
            for (int i = 0; i < encoded.length; i += 255) {
                int count = Math.min(255, encoded.length - i);
                out.write(count);
                out.write(encoded, i, count);
            }
            out.write(0); // End of image data sub-blocks.
            hasFrames = true;
        }

        void finish() throws IOException {
            if (!hasFrames) throw new IOException("No video frames could be extracted");
            out.write(0x3B);
            out.flush();
        }

        private void ascii(String value) throws IOException {
            out.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        private void word(int n) throws IOException {
            out.write(n & 255);
            out.write((n >> 8) & 255);
        }

        private static byte[] lzw(byte[] pixels) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int clear = 256, end = 257, next = 258, bits = 9;
            Map<Integer, Integer> dictionary = new HashMap<>();
            int[] state = new int[2]; // bit accumulator and number of accumulated bits
            emit(result, state, clear, bits);
            int prefix = pixels[0] & 255;
            for (int i = 1; i < pixels.length; i++) {
                int value = pixels[i] & 255;
                int key = (prefix << 8) | value;
                Integer found = dictionary.get(key);
                if (found != null) {
                    prefix = found;
                } else {
                    emit(result, state, prefix, bits);
                    if (next < 4096) {
                        dictionary.put(key, next++);
                        // GIF decoders grow the code width one code after the encoder.
                        if (next > (1 << bits) && bits < 12) bits++;
                    } else {
                        emit(result, state, clear, bits);
                        dictionary.clear();
                        bits = 9;
                        next = 258;
                    }
                    prefix = value;
                }
            }
            emit(result, state, prefix, bits);
            emit(result, state, end, bits);
            if (state[1] > 0) result.write(state[0] & 255);
            return result.toByteArray();
        }

        private static void emit(ByteArrayOutputStream out, int[] state, int code, int bits) {
            state[0] |= code << state[1];
            state[1] += bits;
            while (state[1] >= 8) {
                out.write(state[0] & 255);
                state[0] >>>= 8;
                state[1] -= 8;
            }
        }
    }
}
