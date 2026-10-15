package com.sghmire.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brand logos from Simple Icons (simpleicons.org, CC0) and custom tile images from a link.
 * Everything is downloaded once and kept as small files on disk; nothing stays in memory
 * except the finished tile picture, which replaces the banner rather than adding to it.
 */
public final class Logos {
    private static final String TAG = "FlowLogos";

    /** Packages whose Simple Icons name isn't simply their label. */
    private static final Map<String, String> SLUGS = new HashMap<>();
    static {
        SLUGS.put("com.android.vending", "googleplay");
        SLUGS.put("com.google.android.youtube.tv", "youtube");
        SLUGS.put("com.google.android.youtube.tvmusic", "youtubemusic");
        SLUGS.put("com.netflix.ninja", "netflix");
        SLUGS.put("com.plexapp.android", "plex");
        SLUGS.put("com.spotify.tv.android", "spotify");
        SLUGS.put("com.disney.disneyplus", "disneyplus");
        SLUGS.put("com.hbo.hbonow", "hbomax");
        SLUGS.put("com.tubitv", "tubi");
        SLUGS.put("tv.twitch.android.app", "twitch");
    }

    /** A logo: the SVG path data and the brand's color. */
    public static final class Logo {
        public final String path;
        public final int color;

        Logo(String path, int color) {
            this.path = path;
            this.color = color;
        }
    }

    private Logos() {}

    static String slugFor(String pkg, String label) {
        String s = SLUGS.get(pkg);
        if (s != null) return s;
        String l = label.toLowerCase(Locale.ROOT)
                .replace("+", "plus").replace(".", "dot").replace("&", "and");
        return l.replaceAll("[^a-z0-9]", "");
    }

    private static File dir(Context c, String name) {
        File d = new File(c.getFilesDir(), name);
        d.mkdirs();
        return d;
    }

    /** The cached logo for an app, or null if there isn't one (yet). */
    public static Logo cached(Context c, String pkg) {
        File f = new File(dir(c, "logos"), pkg + ".svg");
        if (!f.exists()) return null;
        try {
            return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Downloads the app's logo if it has never been looked up. Call off the main thread. */
    public static void prefetch(Context c, String pkg, String label) {
        File svg = new File(dir(c, "logos"), pkg + ".svg");
        File none = new File(dir(c, "logos"), pkg + ".none");
        if (svg.exists() || none.exists()) return;
        String slug = slugFor(pkg, label);
        if (slug.isEmpty()) return;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://cdn.simpleicons.org/" + slug).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            int code = conn.getResponseCode();
            if (code == 404) {
                none.createNewFile(); // Simple Icons has no logo for it; don't ask again
                return;
            }
            if (code != 200) return;
            byte[] body = readAll(conn.getInputStream(), 256 * 1024);
            if (parse(new String(body, StandardCharsets.UTF_8)) == null) return;
            try (FileOutputStream out = new FileOutputStream(svg)) {
                out.write(body);
            }
        } catch (Exception e) {
            Log.w(TAG, "logo for " + pkg + ": " + e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final Pattern FILL = Pattern.compile("fill=\"#([0-9a-fA-F]{6})\"");
    private static final Pattern D = Pattern.compile("<path[^>]*\\sd=\"([^\"]+)\"");

    static Logo parse(String svg) {
        Matcher d = D.matcher(svg);
        if (!d.find()) return null;
        Matcher f = FILL.matcher(svg);
        int color = f.find() ? Color.parseColor("#" + f.group(1)) : 0xFF2A2C30;
        return new Logo(d.group(1), color);
    }

    // ---------------------------------------------------------------- Custom images

    public static File imageFile(Context c, String pkg) {
        return new File(dir(c, "images"), pkg + ".img");
    }

    /** Downloads a picture for a tile. Call off the main thread. Returns true when saved. */
    public static boolean downloadImage(Context c, String pkg, String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return false;
            byte[] body = readAll(conn.getInputStream(), 8 * 1024 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(body, 0, body.length, o);
            if (o.outWidth <= 0) return false; // not an image
            try (FileOutputStream out = new FileOutputStream(imageFile(c, pkg))) {
                out.write(body);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "image for " + pkg + ": " + e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The saved picture, decoded no larger than needed for a tile at 2x. */
    public static Bitmap image(Context c, String pkg, int reqW, int reqH) {
        File f = imageFile(c, pkg);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IllegalStateException("too large");
            }
            return out.toByteArray();
        }
    }
}
