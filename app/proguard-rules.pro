# --- 防止 JAudiotagger 被混淆 (Deep Scan) ---
# JAudiotagger 使用反射，需要保留其内部结构
-keep class org.jaudiotagger.** { *; }
-keep interface org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# --- 保持数据模型和 Room 实体 ---
# 确保 Song 和 WebDavAccount 等数据类不被混淆
-keep class top.sparkfade.webdavplayer.data.model.** { *; }

# --- 保持 Hilt/DI 需要的类 ---
# Hilt 需要保留注解，防止编译失败
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.HiltViewModel class *

# --- 保持 Room/Parcelable 所需的构造函数/字段 ---
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}
-keep class top.sparkfade.webdavplayer.data.model.** {
  <init>(...);
}