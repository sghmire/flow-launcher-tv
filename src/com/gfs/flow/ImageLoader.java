package com.gfs.flow;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small async image loader: downsamples to the target size and keeps a bounded memory cache. */
public class ImageLoader {
    private static final String TAG = "FlowImages";
    private final ContentResolver resolver;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    public ImageLoader(ContentResolver resolver) {
        this.resolver = resolver;
    }

    public void load(String uri, ImageView target, int reqW, int reqH) {
        target.setTag(uri);
        Bitmap cached = cache.get(uri);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bm = decode(uri, reqW, reqH);
            if (bm == null) return;
            cache.put(uri, bm);
            target.post(() -> {
                if (!uri.equals(target.getTag())) return;
                target.setAlpha(0f);
                target.setImageBitmap(bm);
                target.animate().alpha(1f).setDuration(HomeActivity.ms(220)).start();
            });
        });
    }

    private Bitmap decode(String uri, int reqW, int reqH) {
        try {
            byte[] data = read(uri);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            Log.w(TAG, "decode failed " + uri + ": " + t);
            return null;
        }
    }

    private byte[] read(String uri) throws Exception {
        InputStream in;
        HttpURLConnection conn = null;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            in = conn.getInputStream();
        } else {
            in = resolver.openInputStream(Uri.parse(uri));
        }
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
            if (conn != null) conn.disconnect();
        }
    }
}
