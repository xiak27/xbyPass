package com.example.phoneserver

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.function.Consumer
import java.util.zip.ZipInputStream

object Core {
    lateinit var ctx: Context
    lateinit var root: File
    val logBuf = StringBuilder()
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var running = false
    @Volatile var status = "未启动"
    @Volatile var port = 8080
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var proc: Process? = null

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        root = File(ctx.filesDir, "projects").apply { mkdirs() }
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
    }

    private val site: String get() = File(ctx.filesDir, "site").absolutePath
    private fun py() = Python.getInstance().getModule("pyrun")
    private fun sink() = Consumer<String> { log(it) }

    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    fun update() {
        if (pending.compareAndSet(false, true))
            main.postDelayed({ pending.set(false); listener?.invoke() }, 120)
    }

    fun log(s: String) {
        synchronized(logBuf) {
            logBuf.append(s)
            if (logBuf.length > 60000) logBuf.delete(0, 20000)
        }
        update()
    }

    fun ips(): List<String> {
        val l = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    val h = a.hostAddress ?: continue
                    if (!h.contains(':') && a.isSiteLocalAddress) l.add(h)
                }
            }
        } catch (e: Exception) { }
        return l
    }

    fun defaultCmd(dir: File, port: Int): String {
        for (f in listOf("app.py", "main.py", "server.py", "run.py"))
            if (File(dir, f).exists()) return "python $f"
        return "python -m http.server $port --bind 0.0.0.0 --directory ."
    }

    // ---------- GitHub 导入 ----------
    fun importRepo(spec: String, token: String): String {
        var s = spec.trim().removeSuffix("/").removeSuffix(".git")
        for (p in listOf("https://github.com/", "http://github.com/", "github.com/", "git@github.com:"))
            if (s.startsWith(p)) { s = s.removePrefix(p); break }
        val parts = s.split("/")
        if (parts.size < 2) throw Exception("格式应为 owner/repo 或 GitHub 链接")
        val owner = parts[0]
        val repo = parts[1]
        val branch = if (parts.size >= 4 && parts[2] == "tree") parts.drop(3).joinToString("/") else ""
        val api = "https://api.github.com/repos/$owner/$repo/zipball" + (if (branch.isNotEmpty()) "/$branch" else "")
        val c = URL(api).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "PhoneServer")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        c.connectTimeout = 20000
        c.readTimeout = 60000
        if (c.responseCode != 200) throw Exception("GitHub 返回 ${c.responseCode}（仓库不存在，或需要令牌）")
        val dest = File(root, repo)
        dest.deleteRecursively()
        dest.mkdirs()
        val base = dest.canonicalPath
        ZipInputStream(c.inputStream).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val f = File(dest, name)
                if (!f.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o -> z.copyTo(o) }
                }
            }
        }
        return repo
    }

    // ---------- 命令 ----------
    fun runLine(dir: File, line: String): Int {
        val t = line.trim()
        return try {
            when {
                t == "python" || t.startsWith("python ") || t.startsWith("python3 ") ->
                    py().callAttr("run_line", t, dir.absolutePath, site, sink()).toInt()
                t.startsWith("pip ") ->
                    py().callAttr("pip_cmd", t, dir.absolutePath, site, sink()).toInt()
                else -> shell(dir, t)
            }
        } catch (e: Exception) {
            log("错误: ${e.message}\n")
            1
        }
    }

    private fun shell(dir: File, cmd: String): Int {
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(dir).redirectErrorStream(true)
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        val p = pb.start()
        proc = p
        p.inputStream.bufferedReader().use { r ->
            val buf = CharArray(2048)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                log(String(buf, 0, n))
            }
        }
        return p.waitFor()
    }

    fun interrupt() {
        proc?.destroy()
        try { py().callAttr("stop") } catch (e: Exception) { }
    }

    // ---------- 服务 ----------
    fun start(dir: File, cmd: String, p: Int) {
        if (running) return
        running = true
        port = p
        status = "准备环境…"
        log("\n=== 启动: $cmd ===\n")
        worker = Thread {
            try {
                val req = File(dir, "requirements.txt")
                if (req.exists()) py().callAttr("ensure", req.absolutePath, site, sink())
                status = "启动中…"
                update()
                Thread { waitPort() }.start()
                runLine(dir, cmd)
            } catch (e: Exception) {
                log("错误: ${e.message}\n")
            }
            running = false
            status = "已停止"
            log("\n[服务已停止]\n")
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }.also { it.start() }
    }

    private fun waitPort() {
        var i = 0
        while (running && i < 60) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                status = "运行中"
                update()
                return
            } catch (e: Exception) { }
            Thread.sleep(500)
            i++
        }
        if (running) { status = "没检测到端口 $port（脚本可能用了别的端口）"; update() }
    }

    fun stop() {
        interrupt()
        Thread {
            Thread.sleep(3000)
            if (worker?.isAlive == true) {
                log("\n强制结束进程…\n")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }.start()
    }
}
