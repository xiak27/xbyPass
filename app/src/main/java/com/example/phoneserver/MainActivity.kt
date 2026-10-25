package com.example.phoneserver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var prefs: SharedPreferences
    private var project: String? = null
    private var curDir: File? = null
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null
    private var statusView: TextView? = null
    private var urlView: TextView? = null
    private var toggleBtn: Button? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Core.init(this)
        prefs = getSharedPreferences("ps", MODE_PRIVATE)
        project = prefs.getString("project", null)?.takeIf { File(Core.root, it).isDirectory }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this)
        listOf("服务器", "文件", "终端", "导入").forEachIndexed { i, t ->
            val bt = Button(this, null, android.R.attr.borderlessButtonStyle)
            bt.text = t
            bt.setOnClickListener { show(i) }
            bar.addView(bt, LinearLayout.LayoutParams(0, -2, 1f))
        }
        rootView.addView(bar)
        setContentView(rootView)
        Core.listener = { refresh() }
        show(0)
    }

    override fun onDestroy() {
        Core.listener = null
        super.onDestroy()
    }

    // ---------- 通用小部件 ----------
    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }
    private fun label(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size }
    private fun edit(hint: String, value: String = "") = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
    }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
    private fun item(t: String, click: () -> Unit, lc: (() -> Unit)? = null) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(dp(8), dp(12), dp(8), dp(12))
        setOnClickListener { click() }
        if (lc != null) setOnLongClickListener { lc(); true }
    }
    private fun logBox(): ScrollView {
        val tv = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        val sv = ScrollView(this).apply { addView(tv); setBackgroundColor(0x14000000) }
        logView = tv
        logScroll = sv
        return sv
    }
    private fun ask(title: String, ok: (String) -> Unit) {
        val e = EditText(this)
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("确定") { _, _ -> val s = e.text.toString().trim(); if (s.isNotEmpty()) ok(s) }
            .setNegativeButton("取消", null).show()
    }
    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", text))
        toast("已复制")
    }

    private fun show(i: Int) {
        logView = null; logScroll = null; statusView = null; urlView = null; toggleBtn = null
        content.removeAllViews()
        content.addView(when (i) { 0 -> serverView(); 1 -> filesView(); 2 -> termView(); else -> importView() })
        refresh()
    }

    private fun refresh() {
        val txt = synchronized(Core.logBuf) { Core.logBuf.toString() }
        logView?.let {
            if (it.text.length != txt.length) {
                it.text = txt
                logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
            }
        }
        statusView?.text = "状态：" + Core.status
        toggleBtn?.text = if (Core.running) "停止服务" else "启动服务"
        urlView?.text = urlText()
    }

    private fun urlText(): String =
        if (Core.running) Core.ips().joinToString("\n") { "http://$it:${Core.port}" }
            .ifEmpty { "http://127.0.0.1:${Core.port}" }
        else ""

    // ---------- 服务器页 ----------
    private fun serverView(): View {
        val c = col()
        c.addView(label("项目：" + (project ?: "（未选择，先去“导入”或点下面选择）"), 16f))
        c.addView(btn("选择项目") { chooseProject() })
        val dir = project?.let { File(Core.root, it) }
        val cmd = edit("启动命令",
            prefs.getString("cmd:$project", null) ?: (dir?.let { Core.defaultCmd(it, 8080) } ?: ""))
        val port = edit("端口", prefs.getInt("port:$project", 8080).toString())
            .apply { inputType = InputType.TYPE_CLASS_NUMBER }
        c.addView(label("启动命令"))
        c.addView(cmd)
        c.addView(label("端口（用于生成链接，要和脚本里的一致）"))
        c.addView(port)
        toggleBtn = btn("") {
            if (Core.running) Core.stop()
            else {
                val d = dir ?: return@btn toast("请先选择项目")
                val p = port.text.toString().toIntOrNull() ?: 8080
                prefs.edit().putString("cmd:$project", cmd.text.toString()).putInt("port:$project", p).apply()
                startForegroundService(Intent(this, ServerService::class.java))
                Core.start(d, cmd.text.toString(), p)
            }
        }
        c.addView(toggleBtn)
        statusView = label("", 15f)
        urlView = label("", 16f).apply { setTextIsSelectable(true) }
        c.addView(statusView)
        c.addView(urlView)
        c.addView(btn("复制链接") { copy(urlText()) })
        c.addView(logBox(), LinearLayout.LayoutParams(-1, 0, 1f))
        return c
    }

    private fun chooseProject() {
        val names = Core.root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        if (names.isEmpty()) return toast("还没有项目，先去“导入”")
        AlertDialog.Builder(this).setTitle("选择项目")
            .setItems(names.toTypedArray()) { _, i ->
                project = names[i]
                prefs.edit().putString("project", names[i]).apply()
                curDir = null
                show(0)
            }.show()
    }

    // ---------- 文件页 ----------
    private fun filesView(): View {
        val c = col()
        val p = project ?: return c.apply { addView(label("先选择或导入一个项目")) }
        val base = File(Core.root, p)
        if (curDir == null || !curDir!!.path.startsWith(base.path)) curDir = base
        val dir = curDir!!
        c.addView(label(dir.path.removePrefix(Core.root.path), 13f))
        val row = LinearLayout(this)
        row.addView(btn("新建文件") { ask("文件名") { n -> File(dir, n).apply { parentFile?.mkdirs() }.writeText(""); show(1) } })
        row.addView(btn("新建文件夹") { ask("文件夹名") { n -> File(dir, n).mkdirs(); show(1) } })
        c.addView(row)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (dir != base) list.addView(item("⬆ ..", { curDir = dir.parentFile; show(1) }))
        dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { f ->
            list.addView(item((if (f.isDirectory) "📁 " else "📄 ") + f.name,
                { if (f.isDirectory) { curDir = f; show(1) } else openFile(f) },
                { confirmDelete(f) }))
        }
        c.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        c.addView(label("点文件编辑，长按删除", 12f))
        return c
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this).setTitle("删除 ${f.name}？")
            .setPositiveButton("删除") { _, _ -> f.deleteRecursively(); show(1) }
            .setNegativeButton("取消", null).show()
    }

    private fun openFile(f: File) {
        if (f.length() > 1_000_000) return toast("文件太大，不能编辑")
        val bytes = f.readBytes()
        if (bytes.any { it == 0.toByte() }) return toast("二进制文件，不能编辑")
        val c = col()
        c.addView(label(f.name, 15f))
        val ed = EditText(this).apply {
            setText(String(bytes))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        c.addView(ed, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this)
        row.addView(btn("保存") { f.writeText(ed.text.toString()); toast("已保存") })
        row.addView(btn("返回") { show(1) })
        c.addView(row)
        content.removeAllViews()
        content.addView(c)
    }

    // ---------- 终端页 ----------
    private fun termView(): View {
        val c = col()
        val p = project
        c.addView(label("工作目录：" + (p ?: "（未选择项目）"), 13f))
        val cmd = edit("例如 python app.py / pip install requests / ls")
        c.addView(cmd)
        val row = LinearLayout(this)
        row.addView(btn("运行") {
            val d = p?.let { File(Core.root, it) } ?: Core.root
            val s = cmd.text.toString()
            cmd.setText("")
            Core.log("\n$ $s\n")
            Thread { val r = Core.runLine(d, s); Core.log("[退出码 $r]\n") }.start()
        })
        row.addView(btn("中断") { Core.interrupt() })
        row.addView(btn("清空") { synchronized(Core.logBuf) { Core.logBuf.setLength(0) }; refresh() })
        c.addView(row)
        c.addView(logBox(), LinearLayout.LayoutParams(-1, 0, 1f))
        return c
    }

    // ---------- 导入页 ----------
    private fun importView(): View {
        val c = col()
        c.addView(label("从 GitHub 导入", 16f))
        val repo = edit("owner/repo 或 https://github.com/owner/repo")
        val tok = edit("访问令牌（私有仓库才需要，可留空）")
            .apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val st = label("")
        c.addView(repo)
        c.addView(tok)
        c.addView(btn("导入") {
            val s = repo.text.toString()
            val t = tok.text.toString()
            st.text = "下载中…"
            Thread {
                try {
                    val name = Core.importRepo(s, t)
                    runOnUiThread {
                        project = name
                        prefs.edit().putString("project", name).apply()
                        curDir = null
                        toast("已导入 $name")
                        show(0)
                    }
                } catch (e: Exception) {
                    runOnUiThread { st.text = "失败：" + e.message }
                }
            }.start()
        })
        c.addView(btn("新建空项目") {
            ask("项目名") { n ->
                File(Core.root, n).mkdirs()
                project = n
                prefs.edit().putString("project", n).apply()
                curDir = null
                show(0)
            }
        })
        c.addView(st)
        c.addView(label("重新导入同名项目会覆盖原有文件。", 12f))
        return c
    }
}
