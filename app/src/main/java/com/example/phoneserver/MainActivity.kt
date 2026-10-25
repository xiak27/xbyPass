package com.example.phoneserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MR
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var header: TextView
    private lateinit var body: FrameLayout
    private lateinit var nav: BottomNavigationView
    private lateinit var prefs: SharedPreferences
    private var project: String? = null
    private var curDir: File? = null
    private var logView: TextView? = null
    private var sCard: MaterialCardView? = null
    private var sIcon: ImageView? = null
    private var sTitle: TextView? = null
    private var sSub: TextView? = null
    private var toggleBtn: MaterialButton? = null
    private val tabs = listOf(
        "服务器" to R.drawable.ic_server, "文件" to R.drawable.ic_folder,
        "终端" to R.drawable.ic_terminal, "导入" to R.drawable.ic_download)

    override fun onCreate(b: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(b)
        Core.init(this)
        prefs = getSharedPreferences("ps", MODE_PRIVATE)
        project = prefs.getString("project", null)?.takeIf { File(Core.root, it).isDirectory }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = tv("", MR.style.TextAppearance_Material3_TitleLarge, color(MR.attr.colorPrimary)).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setPadding(0, dp(20), 0, dp(12))
        }
        rootView.addView(header, LinearLayout.LayoutParams(-1, -2))
        body = FrameLayout(this)
        rootView.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = BottomNavigationView(this)
        tabs.forEachIndexed { i, t -> nav.menu.add(0, i, i, t.first).setIcon(t.second) }
        nav.setOnItemSelectedListener { show(it.itemId); true }
        rootView.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(rootView)
        Core.listener = { refresh() }
        show(0)
    }

    override fun onDestroy() {
        Core.listener = null
        super.onDestroy()
    }

    // ---------- 通用小部件 ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, 0)
    private fun snack(s: String) = Snackbar.make(body, s, Snackbar.LENGTH_SHORT).setAnchorView(nav).show()

    private fun ripple(): Int {
        val v = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, v, true)
        return v.resourceId
    }

    private fun tv(t: String, style: Int = MR.style.TextAppearance_Material3_BodyMedium, col: Int = 0) =
        TextView(this).apply {
            text = t
            setTextAppearance(style)
            if (col != 0) setTextColor(col)
        }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(16))
    }

    private fun box() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }

    private fun card(bg: Int = 0): MaterialCardView {
        val c = MaterialCardView(this, null, MR.attr.materialCardViewFilledStyle)
        c.radius = dp(24).toFloat()
        if (bg != 0) c.setCardBackgroundColor(bg)
        c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        return c
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun button(t: String, style: Int = 0, f: () -> Unit): MaterialButton {
        val b = MaterialButton(this, null, if (style == 0) MR.attr.materialButtonStyle else style)
        b.text = t
        b.setOnClickListener { f() }
        return b
    }

    private fun btnRow(vararg bs: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        bs.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun field(hint: String, value: String = "", number: Boolean = false, secret: Boolean = false): TextInputLayout {
        val til = TextInputLayout(this, null, MR.attr.textInputOutlinedStyle)
        til.hint = hint
        val et = TextInputEditText(til.context)
        et.setText(value)
        et.setSingleLine(true)
        if (number) et.inputType = InputType.TYPE_CLASS_NUMBER
        if (secret) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        til.addView(et)
        til.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        return til
    }

    private val TextInputLayout.value: String get() = editText?.text?.toString() ?: ""

    private fun infoRow(label: String, value: String, click: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        r.addView(tv(label, MR.style.TextAppearance_Material3_TitleSmall))
        r.addView(tv(value, MR.style.TextAppearance_Material3_BodyMedium, color(MR.attr.colorOnSurfaceVariant)))
        if (click != null) {
            r.setBackgroundResource(ripple())
            r.setOnClickListener { click() }
        }
        return r
    }

    private fun logCard(): MaterialCardView {
        val c = card()
        val l = box()
        l.addView(tv("日志", MR.style.TextAppearance_Material3_TitleMedium))
        l.addView(gap(8))
        logView = tv("", MR.style.TextAppearance_Material3_BodySmall).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        l.addView(logView)
        c.addView(l)
        return c
    }

    private fun ask(title: String, ok: (String) -> Unit) {
        val til = field(title)
        val wrap = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(til) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(wrap)
            .setPositiveButton("确定") { _, _ -> val s = til.value.trim(); if (s.isNotEmpty()) ok(s) }
            .setNegativeButton("取消", null).show()
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", text))
        snack("已复制")
    }

    // ---------- 切换页面 ----------
    private fun show(i: Int) {
        nav.menu.getItem(i).isChecked = true
        header.text = tabs[i].first
        logView = null; sCard = null; sIcon = null; sTitle = null; sSub = null; toggleBtn = null
        body.removeAllViews()
        body.addView(when (i) { 0 -> serverPage(); 1 -> filesPage(); 2 -> termPage(); else -> importPage() })
        refresh()
    }

    private fun refresh() {
        logView?.let {
            val txt = synchronized(Core.logBuf) { Core.logBuf.toString() }.takeLast(6000)
            if (it.text.toString() != txt) it.text = txt
        }
        val run = Core.running
        sCard?.setCardBackgroundColor(color(if (run) MR.attr.colorPrimaryContainer else MR.attr.colorErrorContainer))
        val fg = color(if (run) MR.attr.colorOnPrimaryContainer else MR.attr.colorOnErrorContainer)
        sTitle?.setTextColor(fg)
        sSub?.setTextColor(fg)
        sIcon?.setImageResource(if (run) R.drawable.ic_server else R.drawable.ic_warning)
        sIcon?.setColorFilter(fg)
        sTitle?.text = if (run) "服务运行中" else "服务未启动"
        val u = urlText()
        sSub?.text = if (run) Core.status + (if (u.isNotEmpty()) "\n" + u else "") else Core.status
        toggleBtn?.text = if (run) "停止服务" else "启动服务"
    }

    private fun urlText(): String =
        if (Core.running) Core.ips().joinToString("\n") { "http://$it:${Core.port}" }
            .ifEmpty { "http://127.0.0.1:${Core.port}" }
        else ""

    // ---------- 服务器页 ----------
    private fun serverPage(): View {
        val p = page()
        val sc = card()
        val sr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        sIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) }
        }
        val st = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sTitle = tv("", MR.style.TextAppearance_Material3_TitleLarge)
        sSub = tv("", MR.style.TextAppearance_Material3_BodyMedium).apply { setTextIsSelectable(true) }
        st.addView(sTitle)
        st.addView(sSub)
        sr.addView(sIcon)
        sr.addView(st, LinearLayout.LayoutParams(0, -2, 1f))
        sc.addView(sr)
        sCard = sc
        p.addView(sc)

        val dir = project?.let { File(Core.root, it) }
        val ic = card()
        val il = box()
        il.addView(infoRow("项目", project ?: "未选择，点这里选择") { chooseProject() })
        val cmd = field("启动命令",
            prefs.getString("cmd:$project", null) ?: (dir?.let { Core.defaultCmd(it, 8080) } ?: ""))
        val port = field("端口（要和脚本里的一致）", prefs.getInt("port:$project", 8080).toString(), number = true)
        il.addView(cmd)
        il.addView(port)
        il.addView(gap(8))
        il.addView(infoRow("运行环境", "Python 3.11（已内置，缺的依赖启动时自动安装）"))
        ic.addView(il)
        p.addView(ic)

        toggleBtn = button("启动服务") {
            if (Core.running) Core.stop()
            else {
                val d = dir ?: return@button snack("请先选择项目")
                val pt = port.value.toIntOrNull() ?: 8080
                prefs.edit().putString("cmd:$project", cmd.value).putInt("port:$project", pt).apply()
                startForegroundService(Intent(this, ServerService::class.java))
                Core.start(d, cmd.value, pt)
            }
        }
        p.addView(toggleBtn)
        p.addView(gap(8))
        p.addView(button("复制链接", MR.attr.materialButtonOutlinedStyle) { copy(urlText()) })
        p.addView(gap(12))
        p.addView(logCard())
        return ScrollView(this).apply { addView(p) }
    }

    private fun chooseProject() {
        val names = Core.root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        if (names.isEmpty()) return snack("还没有项目，先去“导入”")
        MaterialAlertDialogBuilder(this).setTitle("选择项目")
            .setItems(names.toTypedArray()) { _, i ->
                project = names[i]
                prefs.edit().putString("project", names[i]).apply()
                curDir = null
                show(0)
            }.show()
    }

    // ---------- 文件页 ----------
    private fun fileRow(icon: String, name: String, click: () -> Unit, lc: (() -> Unit)? = null) =
        tv("$icon  $name", MR.style.TextAppearance_Material3_BodyLarge).apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundResource(ripple())
            setOnClickListener { click() }
            if (lc != null) setOnLongClickListener { lc(); true }
        }

    private fun filesPage(): View {
        val p = page()
        val name = project
        if (name == null) {
            p.addView(tv("先选择或导入一个项目", MR.style.TextAppearance_Material3_BodyLarge))
            return p
        }
        val base = File(Core.root, name)
        if (curDir == null || !curDir!!.path.startsWith(base.path)) curDir = base
        val dir = curDir!!
        val pc = card()
        val pl = box()
        pl.addView(tv(dir.path.removePrefix(Core.root.path), MR.style.TextAppearance_Material3_BodyMedium))
        pc.addView(pl)
        p.addView(pc)
        p.addView(btnRow(
            button("新建文件", MR.attr.materialButtonOutlinedStyle) {
                ask("文件名") { n -> File(dir, n).apply { parentFile?.mkdirs() }.writeText(""); show(1) }
            },
            button("新建文件夹", MR.attr.materialButtonOutlinedStyle) {
                ask("文件夹名") { n -> File(dir, n).mkdirs(); show(1) }
            }))
        p.addView(gap(12))
        val lc = card()
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        if (dir != base) ll.addView(fileRow("⬆", "..", { curDir = dir.parentFile; show(1) }))
        dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { f ->
            ll.addView(fileRow(if (f.isDirectory) "📁" else "📄", f.name,
                { if (f.isDirectory) { curDir = f; show(1) } else openFile(f) },
                { confirmDelete(f) }))
        }
        lc.addView(ll)
        p.addView(lc)
        p.addView(tv("点文件编辑，长按删除", MR.style.TextAppearance_Material3_BodySmall, color(MR.attr.colorOnSurfaceVariant)))
        return ScrollView(this).apply { addView(p) }
    }

    private fun confirmDelete(f: File) {
        MaterialAlertDialogBuilder(this).setTitle("删除 ${f.name}？")
            .setPositiveButton("删除") { _, _ -> f.deleteRecursively(); show(1) }
            .setNegativeButton("取消", null).show()
    }

    private fun openFile(f: File) {
        if (f.length() > 1_000_000) return snack("文件太大，不能编辑")
        val bytes = f.readBytes()
        if (bytes.any { it == 0.toByte() }) return snack("二进制文件，不能编辑")
        header.text = f.name
        val p = page()
        val ec = card()
        val ed = EditText(this).apply {
            setText(String(bytes))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            minLines = 14
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        ec.addView(ed)
        p.addView(ec)
        p.addView(btnRow(
            button("保存") { f.writeText(ed.text.toString()); snack("已保存") },
            button("返回", MR.attr.materialButtonOutlinedStyle) { show(1) }))
        body.removeAllViews()
        body.addView(ScrollView(this).apply { addView(p) })
    }

    // ---------- 终端页 ----------
    private fun termPage(): View {
        val p = page()
        val pj = project
        val cc = card()
        val cl = box()
        cl.addView(tv("工作目录：" + (pj ?: "（未选择项目）"), MR.style.TextAppearance_Material3_BodyMedium))
        val cmd = field("命令", "")
        cl.addView(cmd)
        cl.addView(tv("例如：python app.py、pip install requests、ls",
            MR.style.TextAppearance_Material3_BodySmall, color(MR.attr.colorOnSurfaceVariant)))
        cc.addView(cl)
        p.addView(cc)
        p.addView(btnRow(
            button("运行") {
                val d = pj?.let { File(Core.root, it) } ?: Core.root
                val s = cmd.value
                cmd.editText?.setText("")
                Core.log("\n$ $s\n")
                Thread { val r = Core.runLine(d, s); Core.log("[退出码 $r]\n") }.start()
            },
            button("中断", MR.attr.materialButtonOutlinedStyle) { Core.interrupt() },
            button("清空", MR.attr.materialButtonOutlinedStyle) {
                synchronized(Core.logBuf) { Core.logBuf.setLength(0) }
                refresh()
            }))
        p.addView(gap(12))
        p.addView(logCard())
        return ScrollView(this).apply { addView(p) }
    }

    // ---------- 导入页 ----------
    private fun importPage(): View {
        val p = page()
        val c = card()
        val l = box()
        l.addView(tv("从 GitHub 导入", MR.style.TextAppearance_Material3_TitleMedium))
        val repo = field("owner/repo 或 https://github.com/owner/repo")
        val tok = field("访问令牌（私有仓库才需要，可留空）", secret = true)
        val st = tv("", MR.style.TextAppearance_Material3_BodyMedium)
        l.addView(repo)
        l.addView(tok)
        l.addView(gap(8))
        l.addView(st)
        l.addView(tv("重新导入同名项目会覆盖原有文件。",
            MR.style.TextAppearance_Material3_BodySmall, color(MR.attr.colorOnSurfaceVariant)))
        c.addView(l)
        p.addView(c)
        p.addView(button("导入") {
            val s = repo.value
            val t = tok.value
            st.text = "下载中…"
            Thread {
                try {
                    val name = Core.importRepo(s, t)
                    runOnUiThread {
                        project = name
                        prefs.edit().putString("project", name).apply()
                        curDir = null
                        snack("已导入 $name")
                        show(0)
                    }
                } catch (e: Exception) {
                    runOnUiThread { st.text = "失败：" + e.message }
                }
            }.start()
        })
        p.addView(gap(8))
        p.addView(button("新建空项目", MR.attr.materialButtonOutlinedStyle) {
            ask("项目名") { n ->
                File(Core.root, n).mkdirs()
                project = n
                prefs.edit().putString("project", n).apply()
                curDir = null
                show(0)
            }
        })
        return ScrollView(this).apply { addView(p) }
    }
}
