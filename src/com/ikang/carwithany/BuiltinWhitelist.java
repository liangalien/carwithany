package com.ikang.carwithany;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CarWith 4.0.21 自带白名单的离线兜底表。
 *
 * 数据来源两路合并（运行期优先）：
 *  1) com.carwith.common.Constants 里 AppWhiteItem 构造的反汇编，结构化抽取 64 条；
 *  2) hook 在 CarWith 进程里抓到的真实清单并集 71 条（含微信/车机版音乐/车机导航等
 *     静态常量里没有的条目，例如 com.tencent.mm、com.tencent.qqmusiccar、com.baidu.naviauto）。
 *
 * 运行期仍以 RedirectHook 广播回来的清单为准（会随后续版本累积），这里只是没收到广播时的兜底，
 * 以及给「自带」标签提供分类。分类可能是 other/music/radio/map/media_app。
 */
public final class BuiltinWhitelist {

    public static final String[][] ENTRIES = {
        {"com.autonavi.amapauto", "map"},
        {"com.autonavi.minimap", "map"},
        {"com.baidu.BaiduMap", "map"},
        {"com.baidu.naviauto", "map"},
        {"com.tencent.map", "map"},
        {"com.tencent.wecarnavi", "map"},
        {"com.douyu.rush", "media_app"},
        {"com.miui.androidx.samples", "media_app"},
        {"cmccwm.mobilemusic", "music"},
        {"cn.kuwo.kwmusiccar", "music"},
        {"cn.kuwo.kwmusichd", "music"},
        {"cn.kuwo.player", "music"},
        {"com.apple.android.music", "music"},
        {"com.baidu.netdisk", "music"},
        {"com.kugou.android", "music"},
        {"com.kugou.android.auto", "music"},
        {"com.kugou.android.lite", "music"},
        {"com.luna.music", "music"},
        {"com.miui.fm", "music"},
        {"com.miui.player", "music"},
        {"com.netease.cloudmusic", "music"},
        {"com.salt.music", "music"},
        {"com.sinyee.babybus.story", "music"},
        {"com.spotify.music", "music"},
        {"com.tencent.qqmusic", "music"},
        {"com.tencent.qqmusiccar", "music"},
        {"com.tencent.qqmusicpad", "music"},
        {"com.xs.fm", "music"},
        {"tv.danmaku.bilibilihd", "music"},
        {"air.tv.douyu.android", "other"},
        {"com.android.embdemo", "other"},
        {"com.android.phone", "other"},
        {"com.baidu.haokan", "other"},
        {"com.baidu.searchbox", "other"},
        {"com.cctv.yangshipin.app.androidp", "other"},
        {"com.cmcc.cmvideo", "other"},
        {"com.duowan.kiwi", "other"},
        {"com.example.pptv", "other"},
        {"com.funshion.video.mobile", "other"},
        {"com.hunantv.imgo.activity", "other"},
        {"com.kuaishou.nebula", "other"},
        {"com.miui.embdemo", "other"},
        {"com.miui.weather2", "other"},
        {"com.qiyi.video", "other"},
        {"com.qiyi.video.lite", "other"},
        {"com.sina.weibo", "other"},
        {"com.sinyee.babybus.recommendapp", "other"},
        {"com.smile.gifmaker", "other"},
        {"com.sohu.sohuvideo", "other"},
        {"com.ss.android.article.lite", "other"},
        {"com.ss.android.article.news", "other"},
        {"com.ss.android.article.video", "other"},
        {"com.ss.android.lark", "other"},
        {"com.ss.android.ugc.aweme", "other"},
        {"com.ss.android.ugc.aweme.lite", "other"},
        {"com.ss.android.ugc.live", "other"},
        {"com.ss.android.yumme.video", "other"},
        {"com.sup.android.superb", "other"},
        {"com.tencent.mm", "other"},
        {"com.tencent.news", "other"},
        {"com.tencent.qqlive", "other"},
        {"com.tencent.weishi", "other"},
        {"com.tencent.wemeet.app", "other"},
        {"com.wondertek.miguaikan", "other"},
        {"com.xtc.watch", "other"},
        {"com.yixia.videoeditor", "other"},
        {"com.youku.phone", "other"},
        {"com.yueme.itv", "other"},
        {"com.zhihu.android", "other"},
        {"tv.danmaku.bili", "other"},
        {"app.podcast.cosmos", "radio"},
        {"cn.xuexi.android", "radio"},
        {"com.leting", "radio"},
        {"com.shinyv.cnr", "radio"},
        {"com.sohu.newsclient", "radio"},
        {"com.ximalaya.ting.android", "radio"},
        {"com.ximalaya.ting.lite", "radio"},
        {"io.dushu.fandengreader", "radio"},
    };

    private BuiltinWhitelist() {
    }

    public static Map<String, String> map() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String[] e : ENTRIES) {
            m.put(e[0], e[1]);
        }
        return m;
    }
}
