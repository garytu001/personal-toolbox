"""Destructive task tests on a fresh CI emulator ONLY, using a disposable Dcard-package fixture.
Never runs against a physical device or an emulator with an existing Dcard installation.
"""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid
import zipfile

APP = "com.local.personaltoolbox"
TARGET = "com.sparkslab.dcardreader"


def run(*args, timeout=30):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=timeout).strip()
    except subprocess.CalledProcessError as error:
        print(error.output, flush=True)
        raise


def shell(command):
    return run("adb", "shell", command)


assert shell("getprop ro.kernel.qemu") == "1", "Emulator required; physical devices are forbidden"
assert f"package:{TARGET}" not in shell(f"pm list packages {TARGET}").splitlines(), "Refusing to replace an existing app"
# Gradle's connected tests uninstall their target when finished. Install our helper APK explicitly.
run("adb", "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
apk = shell(f"pm path {APP}").removeprefix("package:")
assert apk.startswith("/data/app/") and apk.endswith(".apk") and "\n" not in apk
user = shell("am get-current-user")


def request(operation, target="-", foreground=False, expected="READY", expired=False, authorize=True):
    nonce = uuid.uuid4().hex
    expires = int(float(shell("cat /proc/uptime").split()[0]) * 1000) + (-1 if expired else 5000)
    args = [operation, target, user, "1" if foreground else "0", str(expires), nonce]
    command = f"CLASSPATH={shlex.quote(apk)} /system/bin/app_process /system/bin {APP}.modules.recents.RecentsCommand "
    output = subprocess.check_output(["adb", "shell", command + " ".join(map(shlex.quote, args))],
        input=f"COMMIT:{nonce}\n" if authorize else "", text=True, stderr=subprocess.STDOUT, timeout=10)
    receipts = [line.strip() for line in output.splitlines() if line.startswith("TBRC1|") and line.strip() != f"TBRC1|{nonce}|PREPARED"]
    assert receipts == [f"TBRC1|{nonce}|{expected}"], output
    print(f"{operation} {target}: {expected}", flush=True)


request("probe")
sdk = Path(os.environ["ANDROID_HOME"])
tools = sdk / "build-tools/35.0.0"
android = sdk / "platforms/android-34/android.jar"
with tempfile.TemporaryDirectory(prefix="recents-fixture-") as directory:
    root = Path(directory)
    source = root / "FixtureActivity.java"
    source.write_text(f"""package {TARGET};
public class FixtureActivity extends android.app.Activity {{
  public void onCreate(android.os.Bundle state) {{
    super.onCreate(state);
    android.widget.TextView text = new android.widget.TextView(this);
    text.setText("Disposable recents fixture"); setContentView(text);
  }}
}}""")
    manifest = root / "AndroidManifest.xml"
    manifest.write_text(f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{TARGET}">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />
<application android:label="Recents fixture"><activity android:name=".FixtureActivity" android:exported="true" /></application>
</manifest>""")
    classes = root / "classes"
    classes.mkdir()
    run("javac", "-source", "8", "-target", "8", "-bootclasspath", str(android), "-d", str(classes), str(source))
    run(str(tools / "d8"), "--lib", str(android), "--output", str(root), *map(str, classes.rglob("*.class")))
    fixture = root / "fixture.apk"
    run(str(tools / "aapt"), "package", "-f", "-M", str(manifest), "-I", str(android), "-F", str(fixture))
    with zipfile.ZipFile(fixture, "a") as archive:
        archive.write(root / "classes.dex", "classes.dex")
    aligned = root / "aligned.apk"
    run(str(tools / "zipalign"), "-p", "4", str(fixture), str(aligned))
    # A disposable fixture-only key; never rely on Gradle's cache or any production signing key.
    fixture_key = root / "fixture.jks"
    run("keytool", "-genkeypair", "-keystore", str(fixture_key), "-storetype", "JKS", "-storepass", "android",
        "-keypass", "android", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
        "-dname", "CN=Disposable Recents Fixture", "-noprompt")
    run(str(tools / "apksigner"), "sign", "--ks", str(fixture_key),
        "--ks-pass", "pass:android", "--key-pass", "pass:android", str(aligned))
    run("adb", "install", str(aligned))
    try:
        shell("am start -W -a android.settings.SETTINGS")
        shell(f"am start -W -n {TARGET}/.FixtureActivity")
        request("remove", TARGET, foreground=True, expected="BLOCKED", authorize=False)
        request("remove", TARGET, foreground=True, expected="REMOVED")
        assert "com.android.settings" in shell("dumpsys activity recents"), "Unrelated card was removed"
        request("remove", TARGET, expected="ABSENT")
        shell(f"am start -W -n {TARGET}/.FixtureActivity")
        shell("am start -W -a android.settings.SETTINGS")
        request("remove", TARGET, foreground=True, expected="BLOCKED")
        request("remove", TARGET, expected="REMOVED")
        assert "com.android.settings" in shell("dumpsys activity recents"), "Background cleanup touched another card"
        request("remove", "com.android.settings", expected="FAILED")
        shell(f"am start -W -n {TARGET}/.FixtureActivity")
        request("remove", TARGET, expected="FAILED", expired=True)
        request("remove", TARGET, foreground=True, expected="REMOVED")
    finally:
        run("adb", "uninstall", TARGET)

print("Background recents framework tests passed", flush=True)
