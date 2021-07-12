package com.forjrking.lubankt

import android.Manifest
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.IntRange
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.forjrking.lubankt.ext.CompressLiveData
import com.forjrking.lubankt.ext.CompressResult
import com.forjrking.lubankt.ext.State
import com.forjrking.lubankt.ext.compressObserver
import com.forjrking.lubankt.helper.MD5Helper
import com.forjrking.lubankt.io.InputStreamAdapter
import com.forjrking.lubankt.io.InputStreamProvider
import com.forjrking.lubankt.parser.ImageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList


/**
 * @description:
 * @author: forjrking
 * @date: 2020/10/10 3:58 PM
 */
class Luban private constructor(private val owner: LifecycleOwner) {

    companion object {

        fun with(context: FragmentActivity): Luban {
            return with(context as LifecycleOwner)
        }

        fun with(fragment: Fragment): Luban {
            return with(fragment.viewLifecycleOwner)
        }

        fun with(owner: LifecycleOwner = ProcessLifecycleOwner.get()): Luban {
            return Luban(owner)
        }
    }

    fun load(inputStream: InputStream) = loadGeneric(inputStream) { it }

    fun load(file: File) = loadGeneric(file) { FileInputStream(it) }

    fun load(path: String) = loadGeneric(path) { FileInputStream(it) }

    fun load(uri: Uri) = loadGeneric(uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && ContextCompat.checkSelfPermission(
                Checker.context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PERMISSION_GRANTED
        ) {
            val photoUri = MediaStore.setRequireOriginal(it)
            Checker.context.contentResolver.openInputStream(photoUri)!!
        } else {
            Checker.context.contentResolver.openInputStream(it)!!
        }
    }

    fun load(bitmap: Bitmap) = loadGeneric(bitmap) {
        val os = ByteArrayOutputStream()
        bitmap.compress(CompressFormat.JPEG, 100, os)
        bitmap.recycle();
        ByteArrayInputStream(os.toByteArray())
    }

    // 把数据转换成 流
    private fun <T> loadGeneric(ts: T, transform: (T) -> InputStream): Builder<T, File> {

        val provider = object : InputStreamAdapter<T>() {
            @Throws(IOException::class)
            override fun openInternal(): InputStream {
                return transform.invoke(src)
            }

            override fun fileSizeInternal(): Long {
                return when (src) {
                    is String -> {
                        File(src as String).length()
                    }
                    is File -> {
                        (src as File).length()
                    }
                    is Uri -> {
                        val afd: AssetFileDescriptor? =
                            Checker.context.contentResolver.openAssetFileDescriptor(
                                src as Uri,
                                "r"
                            )
                        val fileSize = afd?.length ?: 0L
                        afd?.close()
                        fileSize
                    }
                    is Bitmap -> {
                        (src as Bitmap).byteCount.toLong()
                    }
                    else -> {
                        throw IOException("Incoming data type exception, it must be String, File, Uri")
                    }
                }
            }

            override val src: T
                get() = ts

        }

        return SingleRequestBuild(owner, provider)
    }

    fun <T> load(list: Array<T>): Builder<T, List<File>> {
        return load(list.toList())
    }

    fun <T> load(list: List<T>): Builder<T, List<File>> {
        val providers: MutableList<InputStreamProvider<T>> = ArrayList()
        list.forEach {
            providers.add(object : InputStreamAdapter<T>() {
                @Throws(IOException::class)
                override fun openInternal(): InputStream {
                    return when (src) {
                        is String -> {
                            FileInputStream(src as String)
                        }
                        is File -> {
                            FileInputStream(src as File)
                        }
                        is Uri -> {
                            val photoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                MediaStore.setRequireOriginal(src as Uri)
                            } else {
                                src as Uri
                            }
                            Checker.context.contentResolver.openInputStream(photoUri)!!
                        }
                        is Bitmap -> {
                            val os = ByteArrayOutputStream()
                            (src as Bitmap).compress(CompressFormat.JPEG, 100, os)
                            (src as Bitmap).recycle();
                            ByteArrayInputStream(os.toByteArray())
                        }
                        else -> {
                            throw IOException("Incoming data type exception, it must be String, File, Uri")
                        }
                    }
                }

                @Throws(IOException::class)
                override fun fileSizeInternal(): Long {
                    return when (src) {
                        is String -> {
                            File(src as String).length()
                        }
                        is File -> {
                            (src as File).length()
                        }
                        is Uri -> {
                            val afd: AssetFileDescriptor? =
                                Checker.context.contentResolver.openAssetFileDescriptor(
                                    src as Uri,
                                    "r"
                                )
                            val fileSize = afd?.length ?: 0L
                            afd?.close()
                            fileSize
                        }
                        is Bitmap -> {
                            (src as Bitmap).byteCount.toLong()
                        }
                        else -> {
                            throw IOException("Incoming data type exception, it must be String, File, Uri")
                        }
                    }
                }

                override val src: T
                    get() = it
            })
        }
        return MultiRequestBuild(owner, providers)
    }
}

/** DES: 默认目录 */
private const val DEFAULT_DISK_CACHE_DIR = "luban_disk_cache"

/** DES: T 表示源数据 R表示生成结果 */
abstract class Builder<T, R>(private val owner: LifecycleOwner) {

    //质量压缩质量系数 0~100 无损压缩无用
    protected var bestQuality = Checker.calculateQuality(Checker.context)

    //输出目录
    protected var mOutPutDir: String? = null

    // 使用采样率压缩 or 双线性压缩
    protected var mCompress4Sample = true

    //使用并发
    protected var mUseConcurrent = false

    //是否拷贝exif
    protected var mCopyExif = true

    // 忽略压缩大小
    protected var mIgnoreSize = 100 * 1024L

    //输出格式
    protected var mCompressFormat: CompressFormat? = null

    // 重命名或文件重定向
    protected var mRenamePredicate: ((String) -> String)? = null

    // 单个订阅监听
    protected var mCompressLiveData = CompressLiveData<T, R>()

    //压缩过滤器
    protected var mCompressionPredicate: ((T) -> Boolean) = { true }


    fun filter(predicate: (T) -> Boolean): Builder<T, R> {
        mCompressionPredicate = predicate
        return this
    }

    fun rename(rename: ((String) -> String)?): Builder<T, R> {
        mRenamePredicate = rename
        return this
    }

    /**
     * 单个文件压缩回调,多文件压缩设置此回调,会多次调用 onSuccess(..)
     */
    fun compressObserver(compressResult: CompressResult<T, R>.() -> Unit): Builder<T, R> {
        mCompressLiveData.compressObserver(owner, compressResult)
        return this
    }

    fun setOutPutDir(outPutDir: String?): Builder<T, R> {
        mOutPutDir = outPutDir
        return this
    }

    /**
     * 压缩后输出图片格式 只有3种支持,默认自动根据原图获取
     */
    fun format(compressFormat: CompressFormat): Builder<T, R> {
        mCompressFormat = compressFormat
        return this
    }

    /**
     * 是否使用下采样压缩,如果不使用则使用双线性压缩方式
     * 默认使用向下采样压缩
     */
    fun useDownSample(compress4Sample: Boolean): Builder<T, R> {
        this.mCompress4Sample = compress4Sample
        return this
    }

    /**压缩质量0~100*/
    fun quality(@IntRange(from = 1, to = 100) quality: Int): Builder<T, R> {
        this.bestQuality = quality
        return this
    }

    /**
     * 大小忽略  默认 100kb
     */
    fun ignoreBy(size: Long): Builder<T, R> {
        this.mIgnoreSize = size
        return this
    }

    /**
     * 多文件有用,单文件请忽略,开启并行,高效压缩 默认开启
     * @see supportDispatcher 最终是否能并行与系统版本有关请查看 corePoolSize生成
     */
    fun concurrent(useConcurrent: Boolean): Builder<T, R> {
        this.mUseConcurrent = useConcurrent
        return this
    }

    /**
     * 是否拷贝exif，默认为true
     */
    fun copyExif(copyExif: Boolean): Builder<T, R> {
        this.mCopyExif = copyExif
        return this
    }

    companion object {
        //主要作用用于并行执行时候可以限制执行任务个数 防止OOM
        internal val supportDispatcher: ExecutorCoroutineDispatcher

        init {
//          Android O之后Bitmap内存放在native  https://www.jianshu.com/p/d5714e8987f3
            val corePoolSize = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    /** DES: DES：取CPU核心数-1 代码来自协程内部 [kotlinx.coroutines.CommonPool.createPlainPool] */
                    (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    2
                }
                else -> {
                    1
                }
            }
            val threadPoolExecutor = ThreadPoolExecutor(
                corePoolSize, corePoolSize,
                5L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(), CompressThreadFactory()
            )
            // DES：预创建线程 threadPoolExecutor.prestartAllCoreThreads()
            // DES：让核心线程也可以回收
            threadPoolExecutor.allowCoreThreadTimeOut(true)
            // DES：转换为协程调度器
            supportDispatcher = threadPoolExecutor.asCoroutineDispatcher()
        }
    }

    //同步方法对外提供
    @Throws(IOException::class)
    abstract fun get(): R

    //协程异步方法 发射数据到参数 liveData中
    protected abstract fun asyncRun(scope: CoroutineScope, liveData: CompressLiveData<T, R>)

    /**
     * begin compress image with asynchronous
     */
    fun launch() {
        //开启协程
        asyncRun(owner.lifecycleScope, mCompressLiveData)
    }

}

private abstract class AbstractFileBuilder<T, R>(owner: LifecycleOwner) : Builder<T, R>(owner) {

    //挂起函数
    @Suppress("BlockingMethodInNonBlockingContext")
    @Throws(Throwable::class)
    protected suspend fun compress(stream: InputStreamProvider<T>): File =
        withContext(supportDispatcher) {
            return@withContext try {
                if (mOutPutDir.isNullOrEmpty()) {
                    mOutPutDir =
                        Checker.getCacheDir(Checker.context, DEFAULT_DISK_CACHE_DIR)?.absolutePath
                }
                //后缀处理
                val srcStream = stream.rewindAndGet()
                val length = srcStream.available()
                val type = Checker.getType(srcStream)
                //组合一个名字给输出文件
                val cacheName =
                    "${MD5Helper.md5Str("${stream.src.toString()}_$length")}.${type.suffix}"
                val cacheFile = "$mOutPutDir/${mRenamePredicate?.invoke(cacheName) ?: cacheName}"
                //重命名接口
                val outFile = File(cacheFile)
                if (outFile.exists()) {
                    Checker.logger("缓存文件已存在 输出文件:$outFile")
                    return@withContext outFile
                }
                //如果没有指定format 智能获取解码结果
                val format = mCompressFormat ?: type.format
                //图片是否带有透明层
                val decodeConfig =
                    if (type.hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                Checker.logger("源大小:$length 类型:$type 透明层:${type.hasAlpha} 期望质量:${bestQuality} 输出格式:$format 输出文件:$outFile")
                //判断过滤器 开始压缩
                if (mCompressionPredicate.invoke(stream.src) && (mIgnoreSize < length / 1024 || type == ImageType.UNKNOWN)) {
                    val compressEngine = CompressEngine(
                        stream,
                        outFile,
                        mCompress4Sample,
                        mIgnoreSize,
                        bestQuality,
                        format,
                        decodeConfig,
                        mCopyExif
                    )
                    compressEngine.compress()
                } else {
                    //copy文件到临时文件
                    FileOutputStream(outFile).use { fos ->
                        stream.rewindAndGet().copyTo(fos)
                    }
//                    if (mCopyExif) {
//                        Checker.copyExifData(stream.rewindAndGet(), outFile)
//                    }
                    outFile
                }
            } finally {
                stream.close()
            }
        }
}


/**
 * @Des: 用于单个文件压缩
 **/
private class SingleRequestBuild<T>(owner: LifecycleOwner, val provider: InputStreamAdapter<T>) :
    AbstractFileBuilder<T, File>(owner) {
    override fun get(): File = runBlocking {
        compress(provider)
    }

    override fun asyncRun(scope: CoroutineScope, liveData: CompressLiveData<T, File>) {
        scope.launch {
            flow {
                emit(compress(provider))
            }.flowOn(supportDispatcher)
                .onStart {
                    liveData.value = State.Start
                }.onCompletion {
                    liveData.value = State.Completion
                }.catch {
                    //报错后将传出去
                    liveData.value = State.Error(it, provider.src)
                }.collect {
                    liveData.value = State.Success(it)
                }
        }
    }
}

//多文件压缩实现
private class MultiRequestBuild<T>(
    owner: LifecycleOwner,
    val providers: MutableList<InputStreamProvider<T>>
) : AbstractFileBuilder<T, List<File>>(owner) {

    /**一次获取所有而且是顺序压缩*/
    override fun get(): MutableList<File> = runBlocking {
        providers.map { compress(it) }.toMutableList()
    }

    /**并发方式一次2或者多个个任务 如果所有任务都下发内存OOM*/
    override fun asyncRun(scope: CoroutineScope, liveData: CompressLiveData<T, List<File>>) {
        val result by lazy { ArrayList<File>() }

        val handler = CoroutineExceptionHandler { _, exception ->
            Checker.logger("handlerCatch -> $exception")
        }
        scope.launch(handler) {
            // 并行和串行处理
            val taskFlow = if (mUseConcurrent) {
                providers.map {
                    async { compress(it) }
                }.asFlow().map {
                    it.await()
                }
            } else {
                flow {
                    providers.asFlow().map { compress(it) }
                }
            }
            taskFlow.flowOn(supportDispatcher).onStart {
                liveData.value = State.Start
            }.onCompletion {
                liveData.value = State.Completion
                //成功和错误回调在后也 可以调前
                if (it == null) liveData.value = State.Success(result) else liveData.value =
                    State.Error(it)
            }.onEach {
                //排序好的结果收集
                result.add(it)
            }.collect()
        }
    }
}

/**
 * @Des: android用的压缩线程池优化线程优先级
 * @Version: 1.0.0
 **/
private class CompressThreadFactory : ThreadFactory {
    private val group: ThreadGroup
    private val threadNumber = AtomicInteger(1)
    private val namePrefix: String

    companion object {
        private val poolNumber = AtomicInteger(1)
        private const val DEFAULT_PRIORITY = (Process.THREAD_PRIORITY_BACKGROUND
                + Process.THREAD_PRIORITY_MORE_FAVORABLE)
    }

    init {
        val s = System.getSecurityManager()
        group = s?.threadGroup ?: Thread.currentThread().threadGroup!!
        namePrefix = "KLuban-${poolNumber.getAndIncrement()}-thread-"
    }

    override fun newThread(r: java.lang.Runnable): Thread {
        val thread = object : Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0) {
            override fun run() {
                Process.setThreadPriority(DEFAULT_PRIORITY)
                super.run()
            }
        }
        if (thread.isDaemon) thread.isDaemon = false
        if (thread.priority != Thread.NORM_PRIORITY) thread.priority = Thread.NORM_PRIORITY
        return thread
    }
}