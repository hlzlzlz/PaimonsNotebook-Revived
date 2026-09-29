package com.lianyi.paimonsnotebook.common.application

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import cat.ereza.customactivityoncrash.config.CaocConfig
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import com.lianyi.paimonsnotebook.BuildConfig
import com.lianyi.paimonsnotebook.R
import com.lianyi.paimonsnotebook.common.core.enviroment.CoreEnvironment
import com.lianyi.paimonsnotebook.common.service.daily_note_notify.DailyNoteNotifyScheduler
import com.lianyi.paimonsnotebook.common.service.sign_in.AutoSignInScheduler
import com.lianyi.paimonsnotebook.common.database.PaimonsNotebookDatabase
import com.lianyi.paimonsnotebook.common.extension.scope.launchIO
import com.lianyi.paimonsnotebook.common.extension.scope.launchSafeIO
import com.lianyi.paimonsnotebook.common.util.builder.imageLoader
import com.lianyi.paimonsnotebook.common.util.coil.ImageFallbackInterceptor
import com.lianyi.paimonsnotebook.common.util.coil.MergeInterceptor
import com.lianyi.paimonsnotebook.common.util.data_store.PreferenceKeys
import com.lianyi.paimonsnotebook.common.util.data_store.dataStoreValuesFirstLambda
import com.lianyi.paimonsnotebook.common.util.file.FileHelper
import com.lianyi.paimonsnotebook.common.util.image.PaimonsNotebookImageLoader
import com.lianyi.paimonsnotebook.common.util.log.CrashLogger
import com.lianyi.paimonsnotebook.common.util.request.applicationOkHttpClient
import com.lianyi.paimonsnotebook.common.view.CrashScreen
import com.lianyi.paimonsnotebook.ui.screen.splash.view.SplashScreen
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import kotlinx.coroutines.*
import java.io.File
import kotlinx.coroutines.flow.first


class PaimonsNotebookApplication : Application(), ImageLoaderFactory {
    companion object {
        @SuppressLint("StaticFieldLeak")
        private lateinit var mContext: Context

        //当前前台Activity,用于需要在指定界面上方弹出的组件(如极验滑块)
        var currentActivity: Activity? = null
            private set
        val context by lazy {
            mContext
        }

        const val version = BuildConfig.VERSION_NAME

        const val versionCode = BuildConfig.VERSION_CODE

        const val PaimonsNotebookUA = "${CoreEnvironment.PaimonsNotebookUA}/${version}"

        val name by lazy {
            context.getString(R.string.app_name)
        }

        val qqGroupKey by lazy {
            "qhNCaJ5EPHebQIX4-G2mpQu86f-WlAc7"
        }

        //本分支的维护仓库(上游QooLianyi自2024-11停更,最新仍为1.7.1-12,
        //若继续查上游会导致版本比较恒为"已是最新",应用内更新永久失效)
        val githubUrl by lazy {
            "https://github.com/hlzlzlz/PaimonsNotebook"
        }

        //从git上获取最新release
        val latestReleaseUrl by lazy {
            "https://api.github.com/repos/hlzlzlz/PaimonsNotebook/releases/latest"
        }
    }

    override fun onCreate() {
        super.onCreate()
        mContext = applicationContext

        //调用核心环境初始化
        CoreEnvironment.init()

        //自动签到任务调度
        AutoSignInScheduler.ensureScheduled()

        //便笺提醒任务调度
        DailyNoteNotifyScheduler.ensureScheduled()

        //跟踪前台Activity
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
                    currentActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        //crashScreen
        CaocConfig.Builder.create()
            .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT)
            .enabled(true)
            .showErrorDetails(true)
            .showRestartButton(false)
            .logErrorOnRestart(false)
            .minTimeBetweenCrashesMs(300)
            .restartActivity(SplashScreen::class.java)
            .errorActivity(CrashScreen::class.java)
            .apply()

        //崩溃留痕
        //⚠️ 必须在 CaocConfig.apply() 之后:CAOC 在 apply() 里装自己的处理器,
        //   先装会被它覆盖。装在其后,才能链式委托给它(见 CrashLogger.install)。
        CrashLogger.install()

        //release环境
        if (!BuildConfig.DEBUG) {
            //启用AppCenter
            //⚠️ APPCENTER_SECRET 来自 local.properties 的 appcenter.secret,
            //   当前值是占位符(全 0 GUID),即该上报链路实际不生效。
            //   崩溃可见性由 CrashLogger(本地 files/crash.log)兜底。
            AppCenter.start(
                this,
                BuildConfig.APPCENTER_SECRET,
                Analytics::class.java,
                Crashes::class.java
            )
            //执行计划删除
            executeDiskCachePlanDelete()
        }

        //debug环境
        if (BuildConfig.DEBUG) {
            //debug包启用activity生命周期回调
//            initActivityLifecycleCallbacks()

//            StrictMode.enableDefaults()

            //启用LeakedClosableViolation跟踪
//            try {
//                Class.forName("dalvik.system.CloseGuard")
//                    .getMethod("setEnabled", Boolean::class.javaPrimitiveType)
//                    .invoke(null, true)
//                println("dalvik.system.CloseGuard : 启用成功")
//            } catch (e: ReflectiveOperationException) {
//                println("dalvik.system.CloseGuard : 启用失败")
//            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(ApplicationLifecycleObserver())
        FileHelper.clearTempFile()
    }

    //监听全部activity的状态
    private fun initActivityLifecycleCallbacks() {

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                println("activity:${activity.componentName.className} onActivityCreated")
            }

            override fun onActivityStarted(activity: Activity) {
                println("activity:${activity.componentName.className} onActivityStarted")
            }

            override fun onActivityResumed(activity: Activity) {
                println("activity:${activity.componentName.className} onActivityResumed")
            }

            override fun onActivityPaused(activity: Activity) {
                println("activity:${activity.componentName.className} onActivityPaused")
            }

            override fun onActivityStopped(activity: Activity) {
                println("activity:${activity.componentName.className} onActivityStopped")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                println("activity:${activity.componentName.className} onActivitySaveInstanceState")
            }

            override fun onActivityDestroyed(activity: Activity) {
                println("activity:${activity.componentName.className} onActivityDestroyed")
            }
        })

    }

    //最后调用的时间戳与当前时间戳相差大于此数,则判断为无用图片,默认为7天(604800000L)
    private val deleteTimeStampLimit = 604800000L

    //清除计划删除图片文件
    private fun executeDiskCachePlanDelete() {
        //用launchSafeIO:本方法在Application.onCreate中调用,裸launch抛异常会杀进程
        launchSafeIO {
            launchIO {
                val autoClean = dataStoreValuesFirstLambda {
                    this[PreferenceKeys.EnableAutoCleanExpiredImages] ?: true
                }

                if (!autoClean) return@launchIO

                PaimonsNotebookDatabase.database.diskCacheDao.apply {
                    updateAllDataPlanDeleteStatus(System.currentTimeMillis(), deleteTimeStampLimit)

                    /*
                    * ⚠️ 必须走 Coil 的 DiskCache.remove(),**不能直接 File.delete()**。
                    *
                    * 这里原先是:
                    * ```
                    * PaimonsNotebookImageLoader.getCacheImageFileByUrl(it.url)?.delete()
                    * PaimonsNotebookImageLoader.getCacheImageMetadataFileByUrl(it.url)?.delete()
                    * ```
                    * 而 `image_cache/` 目录是 **Coil 的 DiskLruCache** 在管
                    * (见本类 `newImageLoader()` 的 `diskCache(imageCache)`)。
                    * 它的文件名虽然确实等于 `sha256(url).hex()` + `.0`/`.1`
                    * (与 `PaimonsNotebookImageLoader` 的算法一致,故删的是同一批文件),
                    * 但 **DiskLruCache 还维护一份 journal**:
                    *   - 绕过 API 直接删文件 ⇒ journal 仍认为该条目存在
                    *   - 之后 Coil 读到"有记录但文件没了"的条目 ⇒ 当作损坏
                    *   - 表现为**同一张图被反复重新下载**,而本地读取又拿不到
                    *     (即原 TODO 描述的"删除后再次缓存、再从本地读取为 null")
                    * 这正是 `diskCache.remove(key)` 存在的原因:它同时更新 journal。
                    *
                    * key 用 url:与 `getImageRequest` 里的 `.diskCacheKey(url)` 保持一致。
                    * */
                    getPlanDeleteData().first().forEach {
                        imageCache.remove(it.url)
                    }

                    removeAllPlanDeleteData()
                }
            }


            //删除私有目录下的临时文件
            launchIO {
                FileHelper.clearTempFile()
            }
        }
    }

    private val imageCachePath by lazy {
        context.filesDir.resolve("image_cache")
    }

    private val imageCache by lazy {
        DiskCache.Builder()
            .directory(imageCachePath)
            .maxSizePercent(1.0)
            .build()
    }

    override fun newImageLoader(): ImageLoader {
        wipeCorruptedImageCacheOnce()
        return imageLoader {
            components {
                //emptyOkHttpClient默认超时仅10秒,大图传输中断会产生残缺数据导致Failed to decode GIF
                //改用60秒超时且具备连接失败重试能力的客户端
                callFactory(applicationOkHttpClient.newBuilder()
                    .addInterceptor(ImageFallbackInterceptor)
                    .build())

                //注意:coil-gif的GifDecoder(2.6.0)基于android.graphics.Movie,解不动时直接抛
                //IllegalStateException(Failed to decode GIF)而不是返回null,异常会使整个请求失败
                //Android<P上不注册任何GIF解码器,由默认BitmapFactoryDecoder解码GIF静态首帧
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add { result, options, _ -> ImageDecoderDecoder(result.source, options, false) }
                }

                add(MergeInterceptor)
            }
            diskCache(imageCache)
            crossfade(300)
            error(R.drawable.ic_image_error)
            respectCacheHeaders(false)
        }
    }

    //早期版本下载失败时可能残留残缺的缓存文件,一次性清空图片缓存
    private fun wipeCorruptedImageCacheOnce() {
        try {
            val marker = File(filesDir, "image_cache_wiped_20260912")
            if (!marker.exists()) {
                File(filesDir, "image_cache").deleteRecursively()
                marker.createNewFile()
            }
        } catch (_: Exception) {
        }
    }
}