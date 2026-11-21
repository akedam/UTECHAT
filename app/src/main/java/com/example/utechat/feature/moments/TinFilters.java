package com.example.utechat.feature.moments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Frame geometry and artwork are shared with the web client. */
final class TinFilters {
    private static final String DIRECTORY = "tin-filters/";
    static final Filter NATURAL = new Filter("natural", "Không khung", "booth", 1, 1, 0,
            new int[4], 0xFFCBDCED, null, null, 1);
    final List<Filter> entries;
    private final Context app;
    private final LruCache<String, Bitmap> overlays = new LruCache<>(4);

    static final class Filter {
        final String id, label, group, art, anchor;
        final int cells, columns, gap, fill;
        final int[] insets;
        final float scale;
        Filter(String id, String label, String group, int cells, int columns, int gap,
               int[] insets, int fill, String art, String anchor, float scale) {
            this.id = id; this.label = label; this.group = group;
            this.cells = cells; this.columns = columns; this.gap = gap; this.insets = insets;
            this.fill = fill; this.art = art; this.anchor = anchor; this.scale = scale;
        }
        RectF cell(int index) {
            int rows = (cells + columns - 1) / columns;
            float w = (TinPhotos.SIZE - insets[1] - insets[3] - gap * (columns - 1)) / (float) columns;
            float h = (TinPhotos.SIZE - insets[0] - insets[2] - gap * (rows - 1)) / (float) rows;
            float x = insets[3] + (index % columns) * (w + gap);
            float y = insets[0] + (index / columns) * (h + gap);
            return new RectF(x, y, x + w, y + h);
        }
    }

    TinFilters(Context context) {
        this.app = context.getApplicationContext();
        List<Filter> loaded;
        try (var stream = app.getAssets().open(DIRECTORY + "catalog.json");
             var data = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            for (int size; (size = stream.read(chunk)) >= 0;) data.write(chunk, 0, size);
            var records = new JSONArray(new String(data.toByteArray(), StandardCharsets.UTF_8));
            loaded = new ArrayList<>(records.length());
            for (int index = 0; index < records.length(); index++) {
                var option = records.getJSONObject(index);
                var margins = option.getJSONArray("insets");
                int[] insets = {margins.getInt(0), margins.getInt(1), margins.getInt(2), margins.getInt(3)};
                loaded.add(new Filter(option.getString("id"), option.getString("label"), option.getString("group"),
                        option.getInt("cells"), option.getInt("columns"), option.getInt("gap"), insets,
                        Color.parseColor(option.optString("fill", "#cbdced")), option.optString("art", null),
                        option.optString("anchor", null), (float) option.optDouble("scale", 1)));
            }
        } catch (Exception invalid) {
            android.util.Log.w("UTEChatTin", "Missing filter catalogue", invalid);
            loaded = List.of(NATURAL);
        }
        entries = loaded;
    }

    Filter find(String id) {
        for (Filter filter : entries) if (filter.id.equals(id)) return filter;
        return NATURAL;
    }

    String previewUri(Filter filter) { return "file:///android_asset/" + DIRECTORY + filter.art; }

    synchronized Bitmap artwork(Filter filter) throws IOException {
        if (filter.art == null) return null;
        Bitmap cached = overlays.get(filter.art);
        if (cached != null) return cached;
        try (var stream = app.getAssets().open(DIRECTORY + filter.art)) {
            Bitmap result = BitmapFactory.decodeStream(stream);
            if (result == null) throw new IOException("Invalid filter artwork");
            overlays.put(filter.art, result);
            return result;
        }
    }
}
