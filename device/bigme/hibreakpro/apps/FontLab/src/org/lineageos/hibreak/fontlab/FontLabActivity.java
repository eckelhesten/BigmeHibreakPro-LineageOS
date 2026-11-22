/*
 * E-ink font bench: one page of typical text drawn in a given font file and weights.
 *
 *   am start -n org.lineageos.hibreak.fontlab/.FontLabActivity \
 *       --es name inter-450 --es font Inter.ttf --es w "400:450,500:560,700:720" \
 *       --es axes "'opsz' 14"
 *
 * font: a path, or a file name in the app's external files dir ("fonts/"). w maps the page's
 * logical weights (400 body, 500 names, 700 bold) to the font's wght axis. axes: more variation
 * settings for every style. The system's bold-text adjustment is switched off for this page, so
 * the weights are exactly the ones asked for.
 *
 * After layout the page logs where everything is (tag FontLab, JSON) for the bench to crop.
 *
 * With --ez list true the page shows the entries of playlist.txt in the app's external files
 * dir (one per line: name|font|w|axes), starting at --ei i; a tap on the right half shows the
 * next entry, on the left half the previous one.
 */
package org.lineageos.hibreak.fontlab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FontLabActivity extends Activity {
    private static final String TAG = "FontLab";

    static final String S1 = "Recent apps, default apps";
    static final String S2 = "Bluetooth, pairing 2048";
    static final String[] CELLS = {
        "rn", "m", "cl", "d", "vv", "w", "I", "l", "1",
        "O", "0", "e", "c", "o", "a", "3", "8", "B",
        "5", "6", "S", "g", "q", "h", "b", "n", "u",
    };
    static final String PARAGRAPH = "Electronic paper reflects light like ordinary paper, so "
            + "text stays readable in sunlight and the screen uses almost no power between "
            + "updates. In its fast modes the panel shows only black and white, so every "
            + "letter is built from whole pixels.";
    static final String BOLD = "only black and white";

    private final Map<Integer, Typeface> mFaces = new HashMap<>();
    private String mName = "";
    private LinearLayout mRoot;
    private final List<String[]> mList = new ArrayList<>();
    private int mIndex;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration c = new Configuration();
        c.fontWeightAdjustment = 0;
        applyOverrideConfiguration(c);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    private File fontFile(String f) {
        if (f == null || f.isEmpty()) f = "/system/fonts/Roboto-Regular.ttf";
        File file = new File(f);
        if (file.isAbsolute()) return file;
        File dir = getExternalFilesDir("fonts");
        if (dir == null) {
            Log.w(TAG, "external fonts dir unavailable, using default font");
            return new File("/system/fonts/Roboto-Regular.ttf");
        }
        return new File(dir, f);
    }

    private void show(Intent in) {
        mList.clear();
        if (in.getBooleanExtra("list", false)) {
            File base = getExternalFilesDir(null);
            if (base == null) {
                Log.e(TAG, "external files dir unavailable");
            } else {
                try {
                    for (String line : java.nio.file.Files.readAllLines(
                            new File(base, "playlist.txt").toPath())) {
                        if (!line.trim().isEmpty()) mList.add(line.split("\\|", -1));
                    }
                } catch (java.io.IOException e) {
                    Log.e(TAG, "no playlist", e);
                }
            }
        }
        if (!mList.isEmpty()) {
            mIndex = Math.max(0, Math.min(mList.size() - 1, in.getIntExtra("i", 0)));
            showEntry();
        } else {
            show(in.getStringExtra("name"), in.getStringExtra("font"), in.getStringExtra("w"),
                    in.getStringExtra("axes"));
        }
    }

    private void showEntry() {
        String[] e = mList.get(mIndex);
        String name = e.length > 0 ? e[0] : "";
        String font = e.length > 1 ? e[1] : "";
        String w = e.length > 2 ? e[2] : "";
        String axes = e.length > 3 ? e[3] : "";
        show((mIndex + 1) + "/" + mList.size() + "  " + name, font, w, axes);
    }

    private void loadFaces(String fontName, String w, String axes) {
        mFaces.clear();
        File file = fontFile(fontName);
        Map<Integer, Integer> map = new HashMap<>();
        if (w != null && !w.isEmpty()) {
            for (String kv : w.split(",")) {
                String[] p = kv.trim().split(":");
                if (p.length != 2) continue;
                try {
                    map.put(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
                } catch (NumberFormatException ignored) {
                    Log.w(TAG, "ignoring invalid weight mapping: " + kv);
                }
            }
        }
        for (int logical : new int[] {400, 500, 700}) {
            int wght = map.containsKey(logical) ? map.get(logical) : logical;
            String fvs = "'wght' " + wght + (axes == null || axes.isEmpty() ? "" : ", " + axes);
            Typeface tf = new Typeface.Builder(file)
                    .setFontVariationSettings(fvs)
                    .setWeight(Math.max(1, Math.min(1000, wght)))
                    .setItalic(false)
                    .setFallback("sans-serif")
                    .build();
            if (tf == null) {
                Log.e(TAG, "cannot load " + file + " " + fvs);
                tf = Typeface.DEFAULT;
            }
            mFaces.put(logical, tf);
        }
    }

    private TextView text(String s, float sp, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(mFaces.get(weight));
        t.setTextColor(Color.BLACK);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void show(String name, String font, String w, String axes) {
        mName = name == null ? "default" : name;
        loadFaces(font, w, axes);

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(Color.WHITE);
        mRoot.setPadding(dp(16), dp(28), dp(16), dp(8));

        TextView label = new TextView(this);
        label.setText(mName);
        label.setTypeface(Typeface.MONOSPACE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTextColor(Color.BLACK);
        label.setTag("label");
        mRoot.addView(label);

        TextView head = text("Good evening", 26, 400);
        head.setTag("headline");
        head.setPadding(0, dp(4), 0, dp(6));
        mRoot.addView(head);

        LinearLayout settings = column("settings");
        settings.addView(row(new String[] {"Network and internet", "Mobile, Wi-Fi, hotspot"}));
        settings.addView(row(new String[] {"Display", "Dark theme, font size, brightness"}));
        mRoot.addView(settings);

        LinearLayout chats = column("chats");
        chats.addView(chat("Åsa Lindqvist", "18:42", "Är du på väg? Vi ses vid stationen."));
        chats.addView(chat("Johan Öberg", "Tue", "Sounds good, I'll bring the tickets (and coffee)."));
        mRoot.addView(chats);

        SpannableStringBuilder para = new SpannableStringBuilder(PARAGRAPH);
        int b = PARAGRAPH.indexOf(BOLD);
        para.setSpan(new TypefaceSpan(mFaces.get(700)), b, b + BOLD.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView p = text("", 16, 400);
        p.setText(para);
        p.setLineSpacing(0, 1.15f);
        p.setTag("paragraph");
        p.setPadding(0, dp(8), 0, dp(10));
        mRoot.addView(p);

        TestView tv = new TestView(this);
        tv.setTag("test");
        mRoot.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        mRoot.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP && !mList.isEmpty()) {
                mIndex = (mIndex + (ev.getX() > v.getWidth() / 2f ? 1 : mList.size() - 1))
                        % mList.size();
                showEntry();
            }
            return true;
        });
        setContentView(mRoot);
        final LinearLayout root = mRoot;
        root.post(() -> {
            if (root == mRoot && !isDestroyed()) logLayout();
        });
    }

    private LinearLayout column(String tag) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setTag(tag);
        return l;
    }

    private View row(String[] lines) {
        LinearLayout l = column(null);
        l.setPadding(0, dp(7), 0, dp(7));
        l.addView(text(lines[0], 16, 400));
        l.addView(text(lines[1], 14, 400));
        return l;
    }

    private View chat(String name, String time, String msg) {
        LinearLayout l = column(null);
        l.setPadding(0, dp(7), 0, dp(7));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = text(name, 16, 500);
        top.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView t = text(time, 12, 400);
        t.setGravity(Gravity.END);
        top.addView(t);
        l.addView(top);
        TextView m = text(msg, 14, 400);
        m.setSingleLine(true);
        m.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.addView(m);
        return l;
    }

    private void rect(StringBuilder sb, String key, View v) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        sb.append(String.format(Locale.US, "\"%s\":[%d,%d,%d,%d],", key, xy[0], xy[1],
                xy[0] + v.getWidth(), xy[1] + v.getHeight()));
    }

    private void logLayout() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":").append(JSONObject.quote(mName)).append(",");
        for (String tag : new String[] {"label", "headline", "settings", "chats", "paragraph"}) {
            rect(sb, tag, mRoot.findViewWithTag(tag));
        }
        TestView tv = mRoot.findViewWithTag("test");
        rect(sb, "test", tv);
        // widths of the samples at 14 sp, per logical weight (layout: a wider font wraps and
        // truncates more)
        Paint pt = new Paint();
        pt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        sb.append("\"width14\":{");
        for (int w : new int[] {400, 500, 700}) {
            pt.setTypeface(mFaces.get(w));
            sb.append(String.format(Locale.US, "\"%d\":%.2f,", w,
                    pt.measureText(S1 + " " + S2 + " " + PARAGRAPH)));
        }
        sb.setLength(sb.length() - 1);
        sb.append("},\"px14\":").append(String.format(Locale.US, "%.3f", pt.getTextSize()));
        sb.append(",\"items\":[");
        int[] xy = new int[2];
        tv.getLocationOnScreen(xy);
        for (TestView.Item it : tv.mItems) {
            sb.append(String.format(Locale.US, "[\"%s\",%d,%.3f,%.3f,%.3f,%d,%d,%.3f],",
                    it.text, it.weight, it.sp, xy[0] + it.x, xy[1] + it.y, it.cellW, it.cellH,
                    tv.px(it.sp)));
        }
        sb.setLength(sb.length() - 1);
        sb.append("]}");
        String s = sb.toString();
        // logcat lines are limited: log in chunks, the bench joins them
        for (int i = 0; i < s.length(); i += 3000) {
            Log.i(TAG, (i == 0 ? "L0 " : "L+ ") + s.substring(i, Math.min(s.length(), i + 3000)));
        }
        Log.i(TAG, "END");
    }

    /** Test glyphs at known positions: confusable cells and sample lines in several sizes. */
    class TestView extends View {
        class Item {
            String text;
            int weight;
            float sp, x, y; // baseline origin, view coordinates
            int cellW, cellH;
        }

        final List<Item> mItems = new ArrayList<>();
        private final Paint mPaint = new Paint();
        private int mHeight;

        TestView(Context c) {
            super(c);
            mPaint.setColor(Color.BLACK);
            mPaint.setAntiAlias(true);
            layoutItems();
        }

        float px(float sp) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                    getResources().getDisplayMetrics());
        }

        private void layoutItems() {
            mItems.clear();
            float y = 0;
            final int cols = 9;
            float size = px(14);
            int cellW = Math.round(size * 2.3f), cellH = Math.round(size * 1.7f);
            for (int i = 0; i < CELLS.length; i++) {
                Item it = new Item();
                it.text = CELLS[i];
                it.weight = 400;
                it.sp = 14;
                it.x = (i % cols) * cellW;
                it.y = y + (i / cols) * cellH + size * 1.1f;
                it.cellW = cellW;
                it.cellH = cellH;
                mItems.add(it);
            }
            y += ((CELLS.length + cols - 1) / cols) * cellH + px(4);
            Object[][] lines = {
                {S1, 400, 12f}, {S2, 400, 12f},
                {S1, 400, 14f}, {S2, 400, 14f},
                {S1, 400, 16f}, {S2, 400, 16f},
                {S1, 500, 14f}, {S1, 700, 14f},
            };
            for (Object[] l : lines) {
                float sp = (Float) l[2];
                float s = px(sp);
                Item it = new Item();
                it.text = (String) l[0];
                it.weight = (Integer) l[1];
                it.sp = sp;
                it.x = 0;
                it.y = y + s * 1.05f;
                it.cellW = 0;
                it.cellH = Math.round(s * 1.45f);
                mItems.add(it);
                y += it.cellH;
            }
            mHeight = Math.round(y + px(4));
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), mHeight);
        }

        @Override
        protected void onDraw(Canvas c) {
            for (Item it : mItems) {
                mPaint.setTypeface(mFaces.get(it.weight));
                mPaint.setTextSize(px(it.sp));
                c.drawText(it.text, it.x, it.y, mPaint);
            }
        }
    }
}
