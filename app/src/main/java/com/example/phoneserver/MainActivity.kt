package com.example.phoneserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(b)
        Core.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { PhoneServerTheme { App() } }
    }
}

@Composable
fun PhoneServerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun copyText(ctx: Context, s: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("url", s))
}

// ---------- 通用组件 ----------
@Composable
fun Cd(color: Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) { Column(Modifier.padding(20.dp), content = content) }
}

@Composable
fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title,
            Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun Info(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LogCard(tick: Int) {
    val txt = synchronized(Core.logBuf) { Core.logBuf.toString() }.takeLast(8000)
    val sc = rememberScrollState()
    LaunchedEffect(txt.length) { sc.scrollTo(sc.maxValue) }
    Cd {
        Text("日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(sc)) {
            SelectionContainer {
                Text(
                    if (txt.isEmpty()) "（暂无输出）" else txt,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun NameDialog(title: String, dismiss: () -> Unit, ok: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) { ok(v.trim()); dismiss() } }) { Text("确定") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
fun ChooseDialog(onPick: (String) -> Unit, dismiss: () -> Unit) {
    val names = Core.root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("选择项目") },
        text = {
            if (names.isEmpty()) Text("还没有项目，先去“导入”页")
            else Column { names.forEach { n -> TextButton(onClick = { onPick(n) }) { Text(n) } } }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }
    )
}

// ---------- 主界面 ----------
@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ps", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        Core.listener = { tick++ }
        onDispose { Core.listener = null }
    }
    var tab by remember { mutableIntStateOf(0) }
    var project by remember {
        mutableStateOf(prefs.getString("project", null)?.takeIf { File(Core.root, it).isDirectory })
    }
    var choosing by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { s -> scope.launch { snack.showSnackbar(s) } }
    val setProject: (String) -> Unit = { n ->
        project = n
        prefs.edit().putString("project", n).apply()
    }
    val tabs = listOf(
        "服务器" to Icons.Filled.Home,
        "文件" to Icons.AutoMirrored.Filled.List,
        "终端" to Icons.Filled.Build,
        "导入" to Icons.Filled.Add
    )
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.second, contentDescription = t.first) },
                        label = { Text(t.first) },
                        alwaysShowLabel = false
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).imePadding()) {
            when (tab) {
                0 -> ServerPage(project, prefs, { choosing = true }, say, tick)
                1 -> FilesPage(project, say)
                2 -> TermPage(project, tick)
                else -> ImportPage({ n -> setProject(n); tab = 0; say("已打开项目 $n") })
            }
        }
    }
    if (choosing) ChooseDialog(onPick = { setProject(it); choosing = false }, dismiss = { choosing = false })
}

// ---------- 服务器页 ----------
@Composable
fun ServerPage(project: String?, prefs: SharedPreferences, pick: () -> Unit, say: (String) -> Unit, tick: Int) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val running = Core.running
    val dir = project?.let { File(Core.root, it) }
    var cmd by remember(project) {
        mutableStateOf(prefs.getString("cmd:$project", null) ?: (dir?.let { Core.defaultCmd(it, 8080) } ?: ""))
    }
    var port by remember(project) { mutableStateOf(prefs.getInt("port:$project", 8080).toString()) }
    val urls = if (running)
        Core.ips().map { "http://$it:${Core.port}" }.ifEmpty { listOf("http://127.0.0.1:${Core.port}") }
    else emptyList()

    Page("服务器") {
        Cd(color = if (running) cs.primaryContainer else cs.errorContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null, modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (running) "服务运行中" else "服务未启动", style = MaterialTheme.typography.titleLarge)
                    Text(Core.status, style = MaterialTheme.typography.bodyMedium)
                    urls.forEach { u ->
                        Text(
                            u,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { copyText(ctx, u); say("已复制 $u") }
                                .padding(top = 6.dp)
                        )
                    }
                }
            }
        }
        Cd {
            Info("项目", project ?: "未选择，点这里选择", pick)
            OutlinedTextField(
                cmd, { cmd = it }, Modifier.fillMaxWidth(),
                label = { Text("启动命令") }, singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                label = { Text("端口（要和脚本里的一致）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Info("运行环境", "Python 3.11（已内置），缺的依赖启动时自动安装")
        }
        Button(
            onClick = {
                if (Core.running) Core.stop()
                else if (dir == null) say("请先选择项目")
                else {
                    val p = port.toIntOrNull() ?: 8080
                    prefs.edit().putString("cmd:$project", cmd).putInt("port:$project", p).apply()
                    ctx.startForegroundService(Intent(ctx, ServerService::class.java))
                    Core.start(dir, cmd, p)
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (running) ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
            else ButtonDefaults.buttonColors()
        ) { Text(if (running) "停止服务" else "启动服务", fontSize = 16.sp) }
        FilledTonalButton(
            onClick = {
                if (urls.isEmpty()) say("服务还没启动")
                else { copyText(ctx, urls.joinToString("\n")); say("已复制链接") }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("复制链接", fontSize = 16.sp) }
        LogCard(tick)
    }
}

// ---------- 文件页 ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(icon: String, name: String, click: () -> Unit, lc: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = click, onLongClick = lc)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(14.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun FilesPage(project: String?, say: (String) -> Unit) {
    if (project == null) Page("文件") { Cd { Text("先去“导入”页导入或新建一个项目") } }
    else FilesBody(project, say)
}

@Composable
fun FilesBody(project: String, say: (String) -> Unit) {
    var editing by remember { mutableStateOf<File?>(null) }
    var cur by remember(project) { mutableStateOf<File?>(null) }
    var ver by remember { mutableIntStateOf(0) }
    var newKind by remember { mutableIntStateOf(0) }   // 0 无，1 文件，2 文件夹
    var del by remember { mutableStateOf<File?>(null) }
    val base = File(Core.root, project)
    val dir = cur ?: base
    val ed = editing

    if (ed != null) {
        Editor(ed, { editing = null; ver++ }, say)
    } else {
        val files = remember(dir, ver) {
            (dir.listFiles()?.toList() ?: emptyList())
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
        Page("文件") {
            Cd { Text(dir.path.removePrefix(Core.root.path), style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { newKind = 1 }, modifier = Modifier.weight(1f).height(52.dp)) { Text("新建文件") }
                FilledTonalButton(onClick = { newKind = 2 }, modifier = Modifier.weight(1f).height(52.dp)) { Text("新建文件夹") }
            }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    if (dir != base) FileRow("⬆", "..", { cur = dir.parentFile }, null)
                    files.forEach { f ->
                        FileRow(
                            if (f.isDirectory) "📁" else "📄", f.name,
                            {
                                if (f.isDirectory) cur = f
                                else if (f.length() > 1_000_000) say("文件太大，不能编辑")
                                else if (runCatching { f.readBytes().any { it == 0.toByte() } }.getOrDefault(true))
                                    say("二进制文件，不能编辑")
                                else editing = f
                            },
                            { del = f }
                        )
                    }
                    if (files.isEmpty()) Text("（空）", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("点文件编辑，长按删除", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (newKind != 0) NameDialog(if (newKind == 1) "文件名" else "文件夹名", { newKind = 0 }) { n ->
        val f = File(dir, n)
        if (newKind == 1) { f.parentFile?.mkdirs(); f.writeText("") } else f.mkdirs()
        ver++
    }
    del?.let { f ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("删除 ${f.name}？") },
            confirmButton = { TextButton(onClick = { f.deleteRecursively(); del = null; ver++ }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("取消") } }
        )
    }
}

@Composable
fun Editor(f: File, close: () -> Unit, say: (String) -> Unit) {
    BackHandler(onBack = close)
    var text by remember(f) { mutableStateOf(runCatching { f.readText() }.getOrDefault("")) }
    Page(f.name) {
        OutlinedTextField(
            text, { text = it },
            Modifier.fillMaxWidth().heightIn(min = 360.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            shape = RoundedCornerShape(20.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { f.writeText(text); say("已保存") },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("保存") }
            FilledTonalButton(onClick = close, modifier = Modifier.weight(1f).height(52.dp)) { Text("返回") }
        }
    }
}

// ---------- 终端页 ----------
@Composable
fun TermPage(project: String?, tick: Int) {
    var cmd by remember { mutableStateOf("") }
    Page("终端") {
        Cd {
            Text("工作目录：" + (project ?: "（未选择项目）"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                cmd, { cmd = it }, Modifier.fillMaxWidth(),
                label = { Text("命令") },
                supportingText = { Text("例如 python app.py、pip install requests、ls") },
                singleLine = true
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val d = project?.let { File(Core.root, it) } ?: Core.root
                    val s = cmd
                    cmd = ""
                    Core.log("\n$ $s\n")
                    Thread { val r = Core.runLine(d, s); Core.log("[退出码 $r]\n") }.start()
                },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("运行") }
            FilledTonalButton(onClick = { Core.interrupt() }, modifier = Modifier.weight(1f).height(52.dp)) { Text("中断") }
            OutlinedButton(
                onClick = { synchronized(Core.logBuf) { Core.logBuf.setLength(0) }; Core.update() },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("清空") }
        }
        LogCard(tick)
    }
}

// ---------- 导入页 ----------
@Composable
fun ImportPage(onDone: (String) -> Unit) {
    var repo by remember { mutableStateOf("") }
    var tok by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    val h = remember { Handler(Looper.getMainLooper()) }
    Page("导入") {
        Cd {
            Text("从 GitHub 导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                repo, { repo = it }, Modifier.fillMaxWidth(),
                label = { Text("owner/repo 或 GitHub 链接") }, singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                tok, { tok = it }, Modifier.fillMaxWidth(),
                label = { Text("访问令牌（私有仓库才需要）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text("重新导入同名项目会覆盖原有文件。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = {
                busy = true
                msg = "下载中…"
                val s = repo
                val t = tok
                Thread {
                    try {
                        val n = Core.importRepo(s, t)
                        h.post { busy = false; msg = ""; onDone(n) }
                    } catch (e: Exception) {
                        h.post { busy = false; msg = "失败：" + e.message }
                    }
                }.start()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("导入", fontSize = 16.sp) }
        FilledTonalButton(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("新建空项目", fontSize = 16.sp)
        }
    }
    if (showNew) NameDialog("项目名", { showNew = false }) { n -> File(Core.root, n).mkdirs(); onDone(n) }
}