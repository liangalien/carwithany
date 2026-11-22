package dev.local.cwwl.drawer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 全应用抽屉（车机横屏宫格，参考 CarWith 首页）。
 *
 * 尺寸策略（重要）：
 * CarWith 投屏层把窗口以 220dpi/400dpi 等密度渲染时，getResources().getDisplayMetrics()
 * 报的 widthPixels 可能只有真实窗口的一半（compat scaling），按它换算会让图标缩水一半。
 * 所以这里一律用 WindowMetrics 的真实像素宽高：
 *   横向量 unitW = 真实宽 / 1024
 *   纵向量 unitH = 真实高 / 576
 * 图标大小由宫格格宽直接算出（cell * 填充率），6 列永远不会被撑破，
 * 同时把每档能给的图标顶到最大。
 * 背景透明（Translucent theme），露出车机画面。
 */
public class AllAppsActivity extends Activity {

    private static final String TAG = "cwwl-drawer";
    private static final String CARWITH_PKG = "com.miui.carlink";
    private static final String ACTION_LAUNCH = "dev.local.cwwl.LAUNCH";
    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_DISPLAY = "display";

    private static final String PREFS = "cwwl_drawer";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_PRESET = "preset";

    private static final float BASE_W = 1024f;
    private static final float BASE_H = 576f;
    private static final int COLUMNS = 6;

    /** 把自己画成 1:1 PNG 落到应用私有目录，用于核对真实渲染尺寸 */
    private static final boolean DUMP_LAYOUT = true;

    private static final String[] PRESET_NAMES = {"大", "更大", "特大"};
    /** 图标占格宽比例 */
    private static final float[] ICON_FILL = {0.70f, 0.82f, 0.94f};
    private static final int[] LABEL = {26, 30, 36};
    private static final int[] GAP = {12, 16, 20};
    private static final int[] ROWSPACE = {22, 30, 38};
    private static final int[] TITLE = {30, 34, 38};
    private static final int[] SEARCH_W = {380, 430, 480};
    private static final int[] SEARCH_H = {54, 64, 74};
    private static final int[] SEARCH_TXT = {22, 25, 28};

    private final List<AppEntry> mAll = new ArrayList<AppEntry>();
    private final List<AppEntry> mShown = new ArrayList<AppEntry>();
    private final List<String> mPinned = new ArrayList<String>();

    private AppsAdapter mAdapter;
    private GridView mGrid;
    private TextView mStatus;
    private EditText mSearch;
    private LinearLayout mRoot;

    private float mUnitW = 1f;
    private float mUnitH = 1f;
    private int mWinW = 0;
    private int mWinH = 0;
    private int mPadPx = 0;
    private int mHSpacePx = 0;
    private int mCellPx = 0;
    private int mIconPx = 0;
    private int mPreset = 0;

    static class AppEntry {
        String label;
        String pkg;
        Drawable icon;
        String searchKey;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        measureWindow();

        mUnitW = mWinW / BASE_W;
        mUnitH = mWinH / BASE_H;
        mPadPx = u(18);
        mHSpacePx = u(4);
        mCellPx = (mWinW - 2 * mPadPx - (COLUMNS - 1) * mHSpacePx) / COLUMNS;
        mPreset = Math.min(2, Math.max(0, getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PRESET, 0)));
        mIconPx = (int) (mCellPx * ICON_FILL[mPreset]);

        Log.i(TAG, "window " + mWinW + "x" + mWinH + " displayId="
                + (getDisplay() == null ? -1 : getDisplay().getDisplayId())
                + " density=" + getResources().getDisplayMetrics().density
                + " dmW=" + getResources().getDisplayMetrics().widthPixels
                + " unitW=" + mUnitW + " unitH=" + mUnitH
                + " cellPx=" + mCellPx + " iconPx=" + mIconPx
                + " labelPx=" + u(LABEL[mPreset]) + " preset=" + PRESET_NAMES[mPreset]);

        loadPinned();

        LinearLayout root = new LinearLayout(this);
        mRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FF2E3238"));
        root.setPadding(mPadPx, v(10), mPadPx, v(6));

        // ---- 顶部：标题 + 计数 + 字号按钮 + 搜索框
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("全部应用");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(TITLE[mPreset]));
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setShadowLayer(u(4), 0, u(2), Color.parseColor("#99000000"));
        top.addView(title);

        mStatus = new TextView(this);
        mStatus.setText("载入中…");
        mStatus.setTextColor(Color.parseColor("#D8DDE4"));
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(17));
        mStatus.setShadowLayer(u(4), 0, u(2), Color.parseColor("#99000000"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = u(12);
        top.addView(mStatus, slp);

        TextView presetBtn = new TextView(this);
        presetBtn.setText("字号 " + PRESET_NAMES[mPreset]);
        presetBtn.setTextColor(Color.parseColor("#DCEBFF"));
        presetBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(17));
        presetBtn.setGravity(Gravity.CENTER);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(Color.parseColor("#33202A3D"));
        chip.setCornerRadius(u(16));
        chip.setStroke(Math.max(1, u(1)), Color.parseColor("#664C6B96"));
        presetBtn.setBackground(chip);
        presetBtn.setPadding(u(14), u(7), u(14), u(7));
        presetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPreset = (mPreset + 1) % PRESET_NAMES.length;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_PRESET, mPreset).apply();
                recreate();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = u(12);
        top.addView(presetBtn, plp);

        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        mSearch = new EditText(this);
        mSearch.setSingleLine(true);
        mSearch.setHint("搜索应用名或包名");
        mSearch.setHintTextColor(Color.parseColor("#C2CAD4"));
        mSearch.setTextColor(Color.WHITE);
        mSearch.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(SEARCH_TXT[mPreset]));
        mSearch.setPadding(u(28), 0, u(28), 0);
        mSearch.setBackground(makeSearchBackground());
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        top.addView(mSearch, new LinearLayout.LayoutParams(
                u(SEARCH_W[mPreset]), v(SEARCH_H[mPreset])));

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 宫格
        mGrid = new GridView(this);
        mGrid.setNumColumns(COLUMNS);
        mGrid.setHorizontalSpacing(mHSpacePx);
        mGrid.setVerticalSpacing(v(ROWSPACE[mPreset]));
        mGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        mGrid.setSelector(android.R.color.transparent);
        mGrid.setPadding(0, v(6), 0, 0);
        mGrid.setClipToPadding(false);
        mGrid.setVerticalScrollBarEnabled(false);

        mAdapter = new AppsAdapter();
        mGrid.setAdapter(mAdapter);
        mGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < mShown.size()) {
                    launchOnCarScreen(mShown.get(position));
                }
            }
        });
        mGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < mShown.size()) {
                    showPinMenu(view, mShown.get(position));
                    return true;
                }
                return false;
            }
        });

        root.addView(mGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        loadAppsAsync();
    }

    /** 取真实窗口像素（绕过 compat scaling 后的 DisplayMetrics） */
    private void measureWindow() {
        int w = -1;
        int h = -1;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowMetrics wm = getWindowManager().getCurrentWindowMetrics();
                w = wm.getBounds().width();
                h = wm.getBounds().height();
            } catch (Throwable t) {
                Log.i(TAG, "WindowMetrics failed: " + t);
            }
        }
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        if (w < h) {
            // 兜底：万一是竖窗，按横屏习惯换算
            int tmp = w;
            w = h;
            h = tmp;
        }
        mWinW = w;
        mWinH = h;
    }

    /** 横向设计量（基准 1024 宽） */
    private int u(int designPx) {
        return (int) Math.ceil(designPx * mUnitW);
    }

    /** 纵向设计量（基准 576 高） */
    private int v(int designPx) {
        return (int) Math.ceil(designPx * mUnitH);
    }

    private GradientDrawable makeSearchBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#4D1A222C"));
        d.setCornerRadius(v(SEARCH_H[mPreset]) / 2f);
        d.setStroke(Math.max(1, u(2)), Color.parseColor("#8C4E5A68"));
        return d;
    }

    // ---------------------------------------------------------------- 数据加载
    private void loadAppsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> list = queryLaunchableApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAll.clear();
                        mAll.addAll(list);
                        applyFilter();
                        if (DUMP_LAYOUT) {
                            mRoot.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    dumpLayout();
                                }
                            }, 1200);
                        }
                    }
                });
            }
        }, "cwwl-drawer-load").start();
    }

    private List<AppEntry> queryLaunchableApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
        List<AppEntry> list = new ArrayList<AppEntry>();
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) {
                continue;
            }
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            CharSequence label = ri.loadLabel(pm);
            e.label = TextUtils.isEmpty(label) ? pkg : label.toString().trim();
            try {
                e.icon = ri.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            e.searchKey = (e.label + " " + pkg).toLowerCase(Locale.ROOT);
            list.add(e);
        }
        return list;
    }

    // ------------------------------------------------------- 过滤 / 置顶 / 排序
    private void applyFilter() {
        String q = mSearch == null ? "" : mSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        mShown.clear();
        for (AppEntry e : mAll) {
            if (TextUtils.isEmpty(q) || e.searchKey.contains(q)) {
                mShown.add(e);
            }
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(mShown, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int pa = mPinned.indexOf(a.pkg);
                int pb = mPinned.indexOf(b.pkg);
                if (pa >= 0 && pb >= 0) {
                    return pa - pb;
                }
                if (pa >= 0) {
                    return -1;
                }
                if (pb >= 0) {
                    return 1;
                }
                int r = collator.compare(a.label, b.label);
                return r != 0 ? r : a.pkg.compareTo(b.pkg);
            }
        });
        mAdapter.notifyDataSetChanged();
        if (mStatus != null) {
            String tail = mPinned.isEmpty() ? "" : " · 置顶 " + mPinned.size();
            mStatus.setText(mShown.size() + " 个" + tail);
        }
    }

    private void showPinMenu(View anchor, final AppEntry entry) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean pinned = mPinned.contains(entry.pkg);
        menu.getMenu().add(Menu.NONE, pinned ? 2 : 1, 0, pinned ? "取消置顶" : "置顶");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == 1) {
                    if (!mPinned.contains(entry.pkg)) {
                        mPinned.add(0, entry.pkg);
                    }
                    savePinned();
                    applyFilter();
                    Toast.makeText(AllAppsActivity.this,
                            "已置顶：" + entry.label, Toast.LENGTH_SHORT).show();
                } else if (item.getItemId() == 2) {
                    mPinned.remove(entry.pkg);
                    savePinned();
                    applyFilter();
                    Toast.makeText(AllAppsActivity.this,
                            "已取消置顶：" + entry.label, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        menu.show();
    }

    private void loadPinned() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString(KEY_PINNED, "");
        mPinned.clear();
        if (!TextUtils.isEmpty(raw)) {
            mPinned.addAll(Arrays.asList(raw.split(",")));
        }
    }

    private void savePinned() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PINNED, TextUtils.join(",", mPinned))
                .apply();
    }

    // ---------------------------------------------------------------- 拉起
    private void launchOnCarScreen(AppEntry entry) {
        int displayId = getDisplay() == null ? -1 : getDisplay().getDisplayId();
        Intent req = new Intent(ACTION_LAUNCH);
        req.setPackage(CARWITH_PKG);
        req.putExtra(EXTRA_PKG, entry.pkg);
        req.putExtra(EXTRA_DISPLAY, displayId);
        sendBroadcast(req);
        Toast.makeText(this, "正在车机屏打开 " + entry.label, Toast.LENGTH_SHORT).show();
        // 拉起的应用和新窗口要 1s 左右才上屏，这里等它起来后自己退出，
        // 否则抽屉窗口会一直盖在目标应用上面。
        mRoot.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 900);
    }

    // ---------------------------------------------------------------- 调试出图
    private void dumpLayout() {
        try {
            if (mRoot.getWidth() <= 0 || mRoot.getHeight() <= 0) {
                Log.i(TAG, "dump skipped: root not laid out");
                return;
            }
            ViewGroup first = (ViewGroup) mGrid.getChildAt(0);
            String sizes = first == null ? "no child" :
                    ("cellView=" + first.getWidth() + "x" + first.getHeight()
                            + " icon=" + first.getChildAt(0).getWidth() + "x" + first.getChildAt(0).getHeight()
                            + " labelPx=" + ((TextView) first.getChildAt(1)).getTextSize());
            Log.i(TAG, "dump root=" + mRoot.getWidth() + "x" + mRoot.getHeight()
                    + " grid=" + mGrid.getWidth() + "x" + mGrid.getHeight()
                    + " " + sizes);

            Bitmap bmp = Bitmap.createBitmap(mRoot.getWidth(), mRoot.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            mRoot.draw(canvas);
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                Log.i(TAG, "dump skipped: no external dir");
                return;
            }
            File out = new File(dir, "drawer.png");
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            bmp.recycle();
            Log.i(TAG, "dump saved " + out.getAbsolutePath());
        } catch (Throwable t) {
            Log.i(TAG, "dump failed " + t);
        }
    }

    // ---------------------------------------------------------------- 适配器
    private class AppsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mShown.size();
        }

        @Override
        public Object getItem(int position) {
            return mShown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout item;
            ImageView icon;
            TextView name;
            if (convertView instanceof LinearLayout) {
                item = (LinearLayout) convertView;
                icon = (ImageView) item.getChildAt(0);
                name = (TextView) item.getChildAt(1);
            } else {
                item = new LinearLayout(AllAppsActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);

                icon = new ImageView(AllAppsActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                item.addView(icon, new LinearLayout.LayoutParams(mIconPx, mIconPx));

                name = new TextView(AllAppsActivity.this);
                name.setTextColor(Color.parseColor("#D2D8E0"));
                name.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(LABEL[mPreset]));
                name.setGravity(Gravity.CENTER);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setShadowLayer(u(4), 0, u(2), Color.parseColor("#CC000000"));
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = v(GAP[mPreset]);
                item.addView(name, nlp);
            }

            AppEntry e = mShown.get(position);
            if (e.icon != null) {
                icon.setImageDrawable(e.icon);
            } else {
                icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
            }
            name.setText(e.label);
            boolean pinned = mPinned.contains(e.pkg);
            name.setTextColor(pinned ? Color.WHITE : Color.parseColor("#D2D8E0"));
            name.setTypeface(pinned
                    ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    : Typeface.DEFAULT);
            return item;
        }
    }
}
