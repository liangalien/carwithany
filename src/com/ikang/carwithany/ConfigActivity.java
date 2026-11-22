package com.ikang.carwithany;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 白名单配置界面（模块 App 自己的 Launcher Activity）。
 *
 * CarWith 4.0.21 的白名单条目是 AppWhiteItem(pkg, minVersion, category, name, mode)，
 * 本地兜底清单里只出现 4 个分类：other / music / radio / map。
 *
 * 单一列表 + 每行一个单选分类：点某个分类 = 把该应用加进这个分类的白名单；
 * 再点已选中的那个 = 取消（等价于不在白名单里 / 把自带条目移除）。点一下立刻保存。
 */
public class ConfigActivity extends Activity {

    private static final String TAG = "cwwl-config";
    private static final String PREFS = "cwwl_extra";
    private static final String CARWITH_PKG = "com.miui.carlink";

    static final String[] CATS = {"other", "music", "radio", "map"};
    static final String[] CAT_LABELS = {"其他", "音乐", "电台", "地图"};

    private final List<AppInfo> mAll = new ArrayList<AppInfo>();
    private final List<AppInfo> mShown = new ArrayList<AppInfo>();
    private final Map<String, Set<String>> mSel = new HashMap<String, Set<String>>();
    /** CarWith 自带白名单：包名 -> 分类（界面里打「自带」标签、默认选中用） */
    private final Map<String, String> mBuiltin = new HashMap<String, String>();
    /** 不在白名单里的 CarWith 自带应用（取消选择或改到别的分类时记下来，hook 侧据此删原条目） */
    private final Set<String> mRemoved = new LinkedHashSet<String>();
    /** 选择顺序（最近的排最前），决定置顶区里的先后 */
    private final List<String> mRecent = new ArrayList<String>();
    /** 手动条目：{包名, 显示名, 分类}，用来给"全部应用"这类自己的 APK 伪装成任意应用登记 */
    private final List<String[]> mCustom = new ArrayList<String[]>();

    private AppAdapter mAdapter;
    private ListView mList;
    private EditText mSearch;
    private TextView mStatus;
    private TextView mCustomView;
    private EditText mPkgEt;
    private EditText mNameEt;
    private TextView mCatBtn;
    private String mCustomCat = "other";
    /** 关于页里的捐赠二维码位图（点击可存到相册） */
    private android.graphics.Bitmap mQrBitmap;

    static class AppInfo {
        String label;
        String pkg;
        Drawable icon;
        String key;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSelection();
        loadBuiltin();
        setContentView(buildUi());
        loadAppsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBuiltin();
        if (mAdapter != null && !mAll.isEmpty()) {
            applyFilter();
        }
    }

    /** 内置清单：先铺离线兜底表，再用 hook 广播回来的真实清单并集覆盖（真实清单会随时长累积变全） */
    private void loadBuiltin() {
        mBuiltin.clear();
        mBuiltin.putAll(BuiltinWhitelist.map());
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("builtin_json", "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pkg = o.optString("pkg", "").trim();
                if (!pkg.isEmpty()) {
                    mBuiltin.put(pkg, o.optString("cat", "other"));
                }
            }
            Log.i(TAG, "builtin merged, total=" + mBuiltin.size() + " (hook json=" + arr.length() + ")");
        } catch (Throwable t) {
            Log.i(TAG, "parse builtin_json failed: " + t);
        }
    }

    // ------------------------------------------------------------- 选择状态持久化
    private void loadSelection() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (String c : CATS) {
            Set<String> set = new LinkedHashSet<String>();
            String raw = sp.getString("cat_" + c, "");
            if (!TextUtils.isEmpty(raw)) {
                for (String p : raw.split(",")) {
                    if (!TextUtils.isEmpty(p.trim())) {
                        set.add(p.trim());
                    }
                }
            }
            mSel.put(c, set);
        }
        // 被移出白名单的自带应用
        mRemoved.clear();
        String rmRaw = sp.getString("rm", "");
        if (!TextUtils.isEmpty(rmRaw)) {
            for (String p : rmRaw.split(",")) {
                if (!TextUtils.isEmpty(p.trim())) {
                    mRemoved.add(p.trim());
                }
            }
        }
        // 选择先后顺序
        mRecent.clear();
        String recentRaw = sp.getString("recent", "");
        if (!TextUtils.isEmpty(recentRaw)) {
            for (String p : recentRaw.split(",")) {
                if (!TextUtils.isEmpty(p.trim()) && !mRecent.contains(p.trim())) {
                    mRecent.add(p.trim());
                }
            }
        }
        // 手动条目：格式 "包名|显示名|分类"，多条用 ; 分隔
        mCustom.clear();
        String customRaw = sp.getString("custom", "");
        if (!TextUtils.isEmpty(customRaw)) {
            for (String line : customRaw.split(";")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 2 && !TextUtils.isEmpty(parts[0].trim())) {
                    String cat = parts.length >= 3 && !TextUtils.isEmpty(parts[2].trim())
                            ? parts[2].trim() : "other";
                    mCustom.add(new String[]{parts[0].trim(), parts[1].trim(), cat});
                }
            }
        }
    }

    /** 每次改动后立刻落盘（commit 同步写，保证 Provider/车机侧马上读到最新值） */
    private void persistSelection() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (String c : CATS) {
            e.putString("cat_" + c, TextUtils.join(",", mSel.get(c)));
        }
        StringBuilder sb = new StringBuilder();
        for (String[] c : mCustom) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(c[0]).append('|').append(c[1]).append('|').append(c[2]);
        }
        e.putString("custom", sb.toString());
        e.putString("rm", TextUtils.join(",", mRemoved));
        e.putString("recent", TextUtils.join(",", mRecent));
        e.commit();
        writeDebugJson();
    }

    /** 额外写一份 JSON 到私有目录，便于排查；失败不影响功能 */
    private void writeDebugJson() {
        try {
            JSONObject json = new JSONObject();
            for (String c : CATS) {
                JSONArray arr = new JSONArray();
                for (String p : mSel.get(c)) {
                    arr.put(p);
                }
                if (arr.length() > 0) {
                    json.put(c, arr);
                }
            }
            json.put("rm", TextUtils.join(",", mRemoved));
            FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), "cwwl_extra.json"));
            fos.write(json.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.i(TAG, "writeDebugJson failed " + t);
        }
    }

    // ------------------------------------------------------------------ 界面
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FFF7F8FA"));
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(titleRow);

        TextView title = new TextView(this);
        title.setText("CarWith 投屏白名单");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#FF1A1A1A"));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView about = new TextView(this);
        about.setText("关于");
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        about.setGravity(Gravity.CENTER);
        about.setPadding(dp(12), dp(6), dp(12), dp(6));
        about.setBackground(pillBox(Color.WHITE, Color.parseColor("#FFD5D9E0")));
        about.setTextColor(Color.parseColor("#FF2F6FED"));
        about.setClickable(true);
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });
        titleRow.addView(about);

        TextView desc = new TextView(this);
        desc.setText("每行右侧的下拉框选分类 = 把该应用加进该分类白名单；选「不加入白名单」= 移出（CarWith 自带的会被剔除）。点一下即自动保存，断开重连一次投屏后生效。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setTextColor(Color.parseColor("#FF666666"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        root.addView(desc, dlp);

        // ---- 手动条目：给自己的 APK 指定"要冒充成哪个应用"
        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp(8);
        root.addView(customRow, crlp);

        mPkgEt = new EditText(this);
        mPkgEt.setSingleLine(true);
        mPkgEt.setHint("包名");
        mPkgEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mPkgEt.setPadding(dp(8), dp(4), dp(8), dp(4));
        mPkgEt.setBackground(box(Color.WHITE, "#FFDDDDDD"));
        customRow.addView(mPkgEt, new LinearLayout.LayoutParams(0, dp(38), 1.3f));

        mNameEt = new EditText(this);
        mNameEt.setSingleLine(true);
        mNameEt.setHint("显示名");
        mNameEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mNameEt.setPadding(dp(8), dp(4), dp(8), dp(4));
        mNameEt.setBackground(box(Color.WHITE, "#FFDDDDDD"));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        nlp.leftMargin = dp(6);
        customRow.addView(mNameEt, nlp);

        mCatBtn = new TextView(this);
        mCatBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mCatBtn.setGravity(Gravity.CENTER);
        mCatBtn.setPadding(dp(8), 0, dp(8), 0);
        mCatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCustomCat = CATS[(indexOfCat(mCustomCat) + 1) % CATS.length];
                mCatBtn.setText(mCustomCat);
            }
        });
        mCustomCat = "other";
        mCatBtn.setText("other");
        mCatBtn.setBackground(box(Color.parseColor("#FFEEF3FF"), "#FFB9CBF0"));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(62), dp(38));
        clp.leftMargin = dp(6);
        customRow.addView(mCatBtn, clp);

        Button addBtn = new Button(this);
        addBtn.setText("添加");
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addBtn.setPadding(0, 0, 0, 0);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addCustomEntry();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(56), dp(38));
        alp.leftMargin = dp(6);
        customRow.addView(addBtn, alp);

        mCustomView = new TextView(this);
        mCustomView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mCustomView.setTextColor(Color.parseColor("#FF444444"));
        LinearLayout.LayoutParams cvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cvlp.topMargin = dp(6);
        root.addView(mCustomView, cvlp);

        // 搜索
        mSearch = new EditText(this);
        mSearch.setSingleLine(true);
        mSearch.setHint("搜索应用名或包名");
        mSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mSearch.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Color.WHITE);
        sb.setCornerRadius(dp(8));
        sb.setStroke(1, Color.parseColor("#FFDDDDDD"));
        mSearch.setBackground(sb);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        root.addView(mSearch, slp);
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

        // 列表
        mList = new ListView(this);
        mList.setDividerHeight(0);
        mList.setCacheColorHint(Color.TRANSPARENT);
        mList.setBackgroundColor(Color.WHITE);
        mAdapter = new AppAdapter();
        mList.setAdapter(mAdapter);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = dp(8);
        root.addView(mList, llp);

        // 底部
        mStatus = new TextView(this);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mStatus.setTextColor(Color.parseColor("#FF666666"));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(8);
        root.addView(mStatus, stlp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        root.addView(btnRow, blp);

        // 中性按钮：清空全部选择
        TextView clear = actionButton("清空全部选择", "#FFFFFFFF", "#FFD5D9E0", "#FF444444");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("清空全部选择",
                        "把所有自加分类和 CarWith 自带条目都移出白名单（等于让车机上一个都不剩）。",
                        "清空", new Runnable() {
                            @Override
                            public void run() {
                                for (String c : CATS) {
                                    mSel.get(c).clear();
                                }
                                mRecent.clear();
                                for (AppInfo a : mAll) {
                                    if (mBuiltin.containsKey(a.pkg)) {
                                        mRemoved.add(a.pkg);
                                    }
                                }
                                persistSelection();
                                applyFilter();
                                toast("已清空全部选择并自动保存");
                            }
                        });
            }
        });
        btnRow.addView(clear, weightLp(0));

        // 危险操作：恢复出厂设置（浅红）
        TextView factory = actionButton("恢复出厂设置", "#FFFFF2F2", "#FFE9BEBE", "#FFC04848");
        factory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("恢复出厂设置",
                        "清空所有自加应用、手动条目和移除记录，回到 CarWith 自带白名单的原样（自带应用全部恢复勾选）。",
                        "恢复", new Runnable() {
                            @Override
                            public void run() {
                                factoryReset();
                            }
                        });
            }
        });
        btnRow.addView(factory, weightLp(1));

        // 主操作：重读自带（浅蓝）
        TextView reRead = actionButton("重读自带", "#FFEEF3FF", "#FFC3D4F7", "#FF2F6FED");
        reRead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(ConfigActivity.this)
                        .setTitle("重读 CarWith 自带清单")
                        .setMessage("作用：清掉本地缓存的 CarWith 白名单，下次连接投屏时重新从 CarWith 进程里抓一份最新的。\n\n"
                                + "什么时候用：CarWith 升级或降级之后。升级时自带条目会变多，降级时旧版本没有的条目会残留，"
                                + "重读一次就按当前版本重新生成，不会漏也不会多。\n\n"
                                + "注意：只影响「自带」标记和默认勾选，你自己加的分类和移除记录都不动。"
                                + "点确认后会先退回离线兜底表（" + BuiltinWhitelist.ENTRIES.length + " 条），重连一次投屏补齐真实清单。")
                        .setPositiveButton("重读", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .remove("builtin_json").remove("builtin_at").commit();
                                loadBuiltin();
                                applyFilter();
                                toast("已清掉缓存的自带清单，现在是离线兜底 " + mBuiltin.size()
                                        + " 条；断开重连一次投屏就会重新抓取");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        btnRow.addView(reRead, weightLp(2));

        refreshCustomView();
        return root;
    }

    private LinearLayout.LayoutParams weightLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        if (index > 0) {
            lp.leftMargin = dp(8);
        }
        return lp;
    }

    /** 圆角描边按钮（用 TextView 自绘，避免系统 Button 自带的内边距/大写/阴影） */
    private TextView actionButton(String text, String fill, String stroke, String textColor) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setPadding(dp(6), 0, dp(6), 0);
        btn.setBackground(pillBox(Color.parseColor(fill), Color.parseColor(stroke)));
        btn.setTextColor(Color.parseColor(textColor));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private GradientDrawable pillBox(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(fill);
        d.setStroke(1, stroke);
        return d;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 统一确认框：标题 + 说明 + 确认/取消 */
    private void confirm(String title, String message, String okText, final Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(okText, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        action.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 关于页：版本、项目地址、捐赠二维码（二维码来自 assets/wechat_qr.jpg） */
    private void showAbout() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(6), dp(20), dp(4));

        TextView name = new TextView(this);
        name.setText("CarWithAny");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.parseColor("#FF1A1A1A"));
        panel.addView(name);

        TextView ver = new TextView(this);
        ver.setText("版本 " + versionName() + " · LSPosed 模块");
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ver.setTextColor(Color.parseColor("#FF888888"));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(2);
        panel.addView(ver, vlp);

        TextView intro = new TextView(this);
        intro.setText("把任意应用按分类加进小米 CarWith 的投屏白名单，让它在车机上能被显示、能被打开；"
                + "自带的「全部应用」入口走不通时，可以用配套的「全部应用」抽屉代替。");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        intro.setTextColor(Color.parseColor("#FF555555"));
        intro.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        panel.addView(intro, ilp);

        TextView repo = new TextView(this);
        repo.setText("项目地址  github.com/liangalien/carwithany");
        repo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        repo.setTextColor(Color.parseColor("#FF2F6FED"));
        repo.setPadding(0, dp(8), 0, 0);
        repo.setClickable(true);
        repo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/liangalien/carwithany")));
                } catch (Throwable t) {
                    toast("没有可用的浏览器");
                }
            }
        });
        panel.addView(repo);

        TextView tip = new TextView(this);
        tip.setText("如果它帮你把车机变得好用了一点，可以扫码请作者喝杯咖啡。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(Color.parseColor("#FF555555"));
        tip.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        panel.addView(tip, tlp);

        ImageView qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackground(box(Color.WHITE, "#FFE5E5E5"));
        qr.setPadding(dp(6), dp(6), dp(6), dp(6));
        try {
            java.io.InputStream in = getAssets().open("wechat_qr.jpg");
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
            if (bm != null) {
                mQrBitmap = bm;
                qr.setImageBitmap(bm);
            }
        } catch (Throwable t) {
            Log.i(TAG, "load qr failed: " + t);
        }
        qr.setClickable(true);
        qr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveQrToGallery();
            }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(220), dp(220));
        qlp.topMargin = dp(10);
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(qr, qlp);

        TextView note = new TextView(this);
        note.setText("微信扫码捐赠 · 点二维码可保存到相册");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(Color.parseColor("#FF888888"));
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp2.topMargin = dp(4);
        panel.addView(note, nlp2);

        TextView howto = new TextView(this);
        howto.setText("扫不了？点一下上面这张二维码，它会存到相册的 Pictures/CarWithAny 里；"
                + "然后打开微信 → 右上角「+」→ 扫一扫 → 右下角「相册」，选中刚存的那张图即可。");
        howto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        howto.setTextColor(Color.parseColor("#FF999999"));
        howto.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        panel.addView(howto, hlp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);

        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    /** 把捐赠二维码存进相册（Pictures/CarWithAny），方便微信「扫一扫 → 相册」选图付款 */
    private void saveQrToGallery() {
        if (mQrBitmap == null) {
            toast("二维码还没加载出来，稍后再试");
            return;
        }
        String name = "CarWithAny_donate_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date())
                + ".jpg";
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CarWithAny");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new java.io.IOException("insert returned null");
            }
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            mQrBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os);
            os.flush();
            os.close();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                cv.clear();
                cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            }
            Log.i(TAG, "qr saved: " + uri);
            toast("已存到相册 Pictures/CarWithAny/" + name + "，微信扫一扫 → 相册选它即可");
        } catch (Throwable t) {
            Log.i(TAG, "save qr failed: " + t);
            // 兜底：写进 App 自己的外部目录，至少能用文件管理器拿到
            try {
                File dir = new File(getExternalFilesDir(null), "donate");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File f = new File(dir, name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                mQrBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush();
                fos.close();
                toast("相册写入失败，已存到 " + f.getAbsolutePath());
            } catch (Throwable t2) {
                toast("保存失败：" + t2);
            }
        }
    }

    private GradientDrawable box(int fill, String stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(8));
        d.setStroke(1, Color.parseColor(stroke));
        return d;
    }

    private static int indexOfCat(String cat) {
        int i = idxOf(cat);
        return i < 0 ? 0 : i;
    }

    /** 找到返回下标，找不到（例如 CarWith 的 media_app 影音类）返回 -1 */
    private static int idxOf(String cat) {
        for (int i = 0; i < CATS.length; i++) {
            if (CATS[i].equals(cat)) {
                return i;
            }
        }
        return -1;
    }

    /** 分类显示名；界面四个分类之外的自带分类给出可读中文（media_app = 影音） */
    private static String labelOf(String cat) {
        int i = idxOf(cat);
        if (i >= 0) {
            return CAT_LABELS[i];
        }
        if ("media_app".equals(cat)) {
            return "影音";
        }
        return cat;
    }

    private static String labelOfCat(String cat) {
        return labelOf(cat);
    }

    private void addCustomEntry() {
        String pkg = mPkgEt.getText().toString().trim();
        String name = mNameEt.getText().toString().trim();
        if (pkg.isEmpty() || !pkg.contains(".")) {
            Toast.makeText(this, "包名不合法", Toast.LENGTH_SHORT).show();
            return;
        }
        // 显示名留空时由 hook 侧用该包的真实应用名，避免冒出包名
        for (String[] c : mCustom) {
            if (c[0].equals(pkg)) {
                c[1] = name;
                c[2] = mCustomCat;
                persistSelection();
                refreshCustomView();
                Toast.makeText(this, "已更新 " + pkg, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mCustom.add(new String[]{pkg, name, mCustomCat});
        persistSelection();
        refreshCustomView();
        mPkgEt.setText("");
        mNameEt.setText("");
        Toast.makeText(this, "已添加 " + pkg + "（" + mCustomCat + "），已自动保存",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshCustomView() {
        if (mCustomView == null) {
            return;
        }
        if (mCustom.isEmpty()) {
            mCustomView.setText("手动条目：无（点“添加”可给任何包名登记一个显示名，比如让 dev.local.cwwl.drawer 显示成“知乎”）");
            return;
        }
        StringBuilder sb = new StringBuilder("手动条目（点一下删除最后一条）：");
        for (String[] c : mCustom) {
            sb.append("\n· ").append(c[1]).append("  [").append(c[2]).append("]  ").append(c[0]);
        }
        mCustomView.setText(sb.toString());
        mCustomView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mCustom.isEmpty()) {
                    String[] last = mCustom.remove(mCustom.size() - 1);
                    persistSelection();
                    refreshCustomView();
                    Toast.makeText(ConfigActivity.this, "已移除 " + last[0], Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // ------------------------------------------------------------------ 数据
    private void loadAppsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                IntentHolder holder = new IntentHolder();
                holder.intent = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(holder.intent, 0);
                final List<AppInfo> list = new ArrayList<AppInfo>();
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) {
                        continue;
                    }
                    AppInfo a = new AppInfo();
                    a.pkg = ri.activityInfo.packageName;
                    if (getPackageName().equals(a.pkg)) {
                        continue;
                    }
                    CharSequence label = ri.loadLabel(pm);
                    a.label = TextUtils.isEmpty(label) ? a.pkg : label.toString().trim();
                    try {
                        a.icon = ri.loadIcon(pm);
                    } catch (Throwable ignored) {
                    }
                    a.key = (a.label + " " + a.pkg).toLowerCase(Locale.ROOT);
                    list.add(a);
                }
                final Collator collator = Collator.getInstance(Locale.CHINA);
                Collections.sort(list, new Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        int r = collator.compare(a.label, b.label);
                        return r != 0 ? r : a.pkg.compareTo(b.pkg);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAll.clear();
                        mAll.addAll(list);
                        applyFilter();
                    }
                });
            }
        }, "cwwl-config-load").start();
    }

    /** 只是为了让内部类里能用 Intent，避免 import 冲突 */
    static class IntentHolder {
        android.content.Intent intent;
    }

    /** 某个应用当前选中的分类；null = 不在白名单里 */
    private String assignedCat(String pkg) {
        for (String c : CATS) {
            Set<String> set = mSel.get(c);
            if (set != null && set.contains(pkg)) {
                return c;
            }
        }
        if (mBuiltin.containsKey(pkg) && !mRemoved.contains(pkg)) {
            return mBuiltin.get(pkg);
        }
        return null;
    }

    private void removeFromAllCats(String pkg) {
        for (String c : CATS) {
            mSel.get(c).remove(pkg);
        }
    }

    private void applyFilter() {
        String q = mSearch == null ? "" : mSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        mShown.clear();
        for (AppInfo a : mAll) {
            if (TextUtils.isEmpty(q) || a.key.contains(q)) {
                mShown.add(a);
            }
        }
        // 已选的置顶：最近选中的排最前，其余（自带默认选的）按拼音
        Collections.sort(mShown, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                boolean sa = assignedCat(a.pkg) != null;
                boolean sb = assignedCat(b.pkg) != null;
                if (sa != sb) {
                    return sa ? -1 : 1;
                }
                if (sa) {
                    int ra = mRecent.indexOf(a.pkg);
                    int rb = mRecent.indexOf(b.pkg);
                    if (ra >= 0 && rb >= 0) {
                        return ra - rb;
                    }
                    if (ra >= 0) {
                        return -1;
                    }
                    if (rb >= 0) {
                        return 1;
                    }
                }
                return 0;
            }
        });
        mAdapter.notifyDataSetChanged();
        refreshStatus();
    }

    private void refreshStatus() {
        if (mStatus == null) {
            return;
        }
        int builtinRemoved = 0;
        for (String p : mRemoved) {
            if (mBuiltin.containsKey(p)) {
                builtinRemoved++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CATS.length; i++) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(CAT_LABELS[i]).append(' ').append(mSel.get(CATS[i]).size());
        }
        mStatus.setText("已选 " + totalSelected() + " 个（置顶）· " + sb
                + " · 自带移出 " + builtinRemoved + " 个 · 共 " + mShown.size() + "/" + mAll.size()
                + " 个 · 改动已自动保存");
    }

    private int totalSelected() {
        int n = 0;
        for (String c : CATS) {
            n += mSel.get(c).size();
        }
        return n;
    }

    /** 下拉选完之后的实际动作：cat 为 null 表示「不加入白名单」 */
    private void setCat(AppInfo a, String cat) {
        String cur = assignedCat(a.pkg);
        boolean builtin = mBuiltin.containsKey(a.pkg);

        if (cat == null) {
            if (cur == null) {
                Toast.makeText(this, a.label + " 本来就不在白名单里", Toast.LENGTH_SHORT).show();
                return;
            }
            removeFromAllCats(a.pkg);
            mRecent.remove(a.pkg);
            if (builtin) {
                mRemoved.add(a.pkg);
            }
            persistSelection();
            applyFilter();
            Toast.makeText(this, "已移出白名单：" + a.label + "（已自动保存）", Toast.LENGTH_SHORT).show();
            return;
        }

        if (cat.equals(cur)) {
            Toast.makeText(this, a.label + " 已在「" + labelOfCat(cat) + "」", Toast.LENGTH_SHORT).show();
            return;
        }

        removeFromAllCats(a.pkg);
        if (builtin && cat.equals(mBuiltin.get(a.pkg))) {
            // 自带应用选回它自己的分类 = 保持原样，不需要注入
            mRemoved.remove(a.pkg);
        } else {
            if (builtin) {
                mRemoved.add(a.pkg); // 原分类里的那条要删掉
            }
            mSel.get(cat).add(a.pkg);
        }
        touchRecent(a.pkg);
        persistSelection();
        applyFilter();
        Toast.makeText(this, "已加入「" + labelOfCat(cat) + "」：" + a.label + "（已自动保存）",
                Toast.LENGTH_SHORT).show();
    }

    /** 点下拉框：弹出分类选项 */
    private void showPicker(final AppInfo a) {
        final String cur = assignedCat(a.pkg);
        final List<String> cats = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        labels.add("不加入白名单");
        cats.add(null);
        if (cur != null && idxOf(cur) < 0) {
            // CarWith 自带但我们界面没有这个分类（media_app 等），单独列一项，避免误显示成「其他」
            labels.add(labelOf(cur));
            cats.add(cur);
        }
        for (int i = 0; i < CATS.length; i++) {
            labels.add(CAT_LABELS[i]);
            cats.add(CATS[i]);
        }
        int sel = 0;
        for (int i = 0; i < cats.size(); i++) {
            String c = cats.get(i);
            if (cur == null ? c == null : (c != null && c.equals(cur))) {
                sel = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setSingleChoiceItems(labels.toArray(new String[0]), sel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                setCat(a, cats.get(which));
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 恢复出厂设置：清掉自加分类、手动条目、移除记录，回到 CarWith 自带原样 */
    private void factoryReset() {
        for (String c : CATS) {
            mSel.get(c).clear();
        }
        mRecent.clear();
        mRemoved.clear();
        mCustom.clear();
        persistSelection();
        refreshCustomView();
        loadBuiltin();
        applyFilter();
        Toast.makeText(this, "已恢复出厂设置（自带清单全部恢复为默认勾选）", Toast.LENGTH_LONG).show();
    }

    /** 记录选择顺序：最近选的放最前面 */
    private void touchRecent(String pkg) {
        mRecent.remove(pkg);
        mRecent.add(0, pkg);
    }

    // ------------------------------------------------------------------ 适配器
    private class AppAdapter extends BaseAdapter {
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
            RowHolder h;
            final AppInfo a = mShown.get(position);
            String assigned = assignedCat(a.pkg);
            boolean builtin = mBuiltin.containsKey(a.pkg);

            if (convertView instanceof LinearLayout) {
                h = (RowHolder) ((LinearLayout) convertView).getTag();
            } else {
                h = buildRow();
            }
            LinearLayout row = h.row;

            if (a.icon != null) {
                h.icon.setImageDrawable(a.icon);
            } else {
                h.icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
            }
            h.text.setText(a.label);
            h.sub.setText(a.pkg);
            boolean selected = assigned != null;
            h.text.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            row.setBackgroundColor(selected ? Color.parseColor("#FFEDF3FF")
                    : Color.parseColor("#FFFFFFFF"));

            if (selected && builtin && assigned.equals(mBuiltin.get(a.pkg))) {
                h.tag.setBackground(pill("#FFE8F0FF"));
                h.tag.setTextColor(Color.parseColor("#FF2F6FED"));
                h.tag.setText("自带");
                h.tag.setVisibility(View.VISIBLE);
            } else if (selected) {
                h.tag.setBackground(pill("#FF2F6FED"));
                h.tag.setTextColor(Color.WHITE);
                h.tag.setText("已选");
                h.tag.setVisibility(View.VISIBLE);
            } else if (builtin) {
                h.tag.setBackground(pill("#FFFFEBEB"));
                h.tag.setTextColor(Color.parseColor("#FFC04848"));
                h.tag.setText("已移除");
                h.tag.setVisibility(View.VISIBLE);
            } else {
                h.tag.setVisibility(View.INVISIBLE);
            }

            // 右侧下拉框：显示当前分类，点开选分类 / 不加入
            if (selected) {
                h.drop.setText(labelOfCat(assigned) + " ▾");
                h.drop.setBackground(pill("#FF2F6FED"));
                h.drop.setTextColor(Color.WHITE);
                h.drop.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                h.drop.setText("不加入 ▾");
                h.drop.setBackground(box(Color.WHITE, "#FFDDDDDD"));
                h.drop.setTextColor(Color.parseColor("#FF777777"));
                h.drop.setTypeface(Typeface.DEFAULT);
            }
            final AppInfo info = a;
            h.drop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPicker(info);
                }
            });
            return row;
        }

        private GradientDrawable pill(String color) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(Color.parseColor(color));
            return g;
        }

        private RowHolder buildRow() {
            RowHolder h = new RowHolder();
            LinearLayout row = new LinearLayout(ConfigActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));

            h.icon = new ImageView(ConfigActivity.this);
            row.addView(h.icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout textCol = new LinearLayout(ConfigActivity.this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tclp.leftMargin = dp(10);
            row.addView(textCol, tclp);

            h.text = new TextView(ConfigActivity.this);
            h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            h.text.setTextColor(Color.parseColor("#FF1A1A1A"));
            h.text.setSingleLine(true);
            h.text.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(h.text);

            h.sub = new TextView(ConfigActivity.this);
            h.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            h.sub.setTextColor(Color.parseColor("#FF888888"));
            h.sub.setSingleLine(true);
            h.sub.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(h.sub);

            h.tag = new TextView(ConfigActivity.this);
            h.tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            h.tag.setSingleLine(true);
            h.tag.setGravity(Gravity.CENTER);
            h.tag.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams taglp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            taglp.leftMargin = dp(4);
            row.addView(h.tag, taglp);

            h.drop = new TextView(ConfigActivity.this);
            h.drop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            h.drop.setSingleLine(true);
            h.drop.setGravity(Gravity.CENTER);
            h.drop.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    dp(92), ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = dp(6);
            row.addView(h.drop, dlp);

            row.setTag(h);
            h.row = row;
            return h;
        }
    }

    static class RowHolder {
        LinearLayout row;
        ImageView icon;
        TextView text;
        TextView sub;
        TextView tag;
        TextView drop;
    }
}
