import sys, os, io, re, json, runpy, shlex, zipfile, traceback, threading
import urllib.request, importlib
import importlib.metadata as md

TID = None


class Out(io.TextIOBase):
    def __init__(self, cb):
        self.cb = cb

    def writable(self):
        return True

    def write(self, s):
        if s:
            self.cb.accept(s)
        return len(s)

    def flush(self):
        pass


def _prep(cwd, site, cb):
    sys.stdout = sys.stderr = Out(cb)
    os.makedirs(site, exist_ok=True)
    os.chdir(cwd)
    for p in (cwd, site):
        if p not in sys.path:
            sys.path.insert(0, p)
    importlib.invalidate_caches()


def run_line(line, cwd, site, cb):
    global TID
    _prep(cwd, site, cb)
    TID = threading.get_ident()
    args = shlex.split(line)
    if args and args[0] in ("python", "python3"):
        args = args[1:]
    try:
        if not args:
            print("Python", sys.version)
            return 0
        if args[0] == "-m":
            sys.argv = args[1:]
            runpy.run_module(args[1], run_name="__main__", alter_sys=True)
        elif args[0] == "-c":
            sys.argv = ["-c"] + args[2:]
            exec(compile(args[1], "<string>", "exec"), {"__name__": "__main__"})
        else:
            sys.argv = args
            runpy.run_path(args[0], run_name="__main__")
        return 0
    except SystemExit as e:
        return 0 if e.code in (None, 0) else 1
    except BaseException:
        traceback.print_exc()
        return 1


def stop():
    import ctypes
    if TID is None:
        return False
    r = ctypes.pythonapi.PyThreadState_SetAsyncExc(
        ctypes.c_ulong(TID), ctypes.py_object(SystemExit))
    return r == 1


# ---------- 迷你 pip：只装纯 Python 的 wheel ----------
def _get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "PhoneServer"})
    return urllib.request.urlopen(req, timeout=60).read()


def _installed(name):
    try:
        md.version(name)
        return True
    except Exception:
        return False


def _install_one(spec, site, done):
    m = re.match(r"\s*([A-Za-z0-9][A-Za-z0-9._-]*)\s*(?:\[[^\]]*\])?\s*(?:==\s*([^\s,;]+))?", spec)
    if not m:
        return
    name, ver = m.group(1), m.group(2)
    key = re.sub(r"[-_.]+", "-", name).lower()
    if key in done:
        return
    done.add(key)
    if _installed(name):
        return
    print("安装 %s ..." % name)
    url = "https://pypi.org/pypi/%s/%s/json" % (name, ver) if ver else "https://pypi.org/pypi/%s/json" % name
    data = json.loads(_get(url))
    wheel = None
    for f in data["urls"]:
        if f["packagetype"] == "bdist_wheel" and f["filename"].endswith("-none-any.whl"):
            wheel = f
            break
    if wheel is None:
        print("  %s 没有纯 Python 版本，无法在手机上安装（可在 app/build.gradle.kts 里预装）" % name)
        return
    zipfile.ZipFile(io.BytesIO(_get(wheel["url"]))).extractall(site)
    importlib.invalidate_caches()
    for r in data["info"].get("requires_dist") or []:
        if ";" in r:
            continue
        _install_one(r, site, done)


def _install_all(reqs, site):
    done = set()
    for r in reqs:
        try:
            _install_one(r, site, done)
        except Exception as e:
            print("  失败: %s" % e)
    importlib.invalidate_caches()
    print("依赖检查完成")
    return 0


def _read_reqs(path):
    out = []
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.split("#")[0].split(";")[0].strip()
        if line and not line.startswith("-"):
            out.append(line)
    return out


def ensure(req_file, site, cb):
    _prep(os.path.dirname(req_file), site, cb)
    return _install_all(_read_reqs(req_file), site)


def pip_cmd(line, cwd, site, cb):
    _prep(cwd, site, cb)
    args = shlex.split(line)[1:]
    if not args or args[0] != "install":
        print("只支持: pip install <包名> 或 pip install -r requirements.txt")
        return 1
    reqs, i, names = [], 0, args[1:]
    while i < len(names):
        if names[i] == "-r" and i + 1 < len(names):
            reqs += _read_reqs(os.path.join(cwd, names[i + 1]))
            i += 2
        else:
            reqs.append(names[i])
            i += 1
    return _install_all(reqs, site)
