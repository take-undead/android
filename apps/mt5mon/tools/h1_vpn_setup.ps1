# MECHEN H1（Android 6.0・userdebug ビルド）で WireGuard を使えるようにする（2026-10-14 に実機で確認した手順）。
#
# この端末は VPN の許可画面（com.android.vpndialogs）が無く、/dev/net/tun の装置ファイルも無い。
# カーネル自体は TUN に対応している（/proc/misc に "200 tun"）ので、root で装置ファイルを作り、
# VPN の許可を AppOps（ACTIVATE_VPN）に直接書けば VpnService が使える。
#   - /dev/net/tun は再起動で消える → 再起動のたびにこのスクリプトを実行する
#   - ACTIVATE_VPN の許可は残る
# 使い方: USB でつないで  powershell -ExecutionPolicy Bypass -File tools\h1_vpn_setup.ps1
param([string]$Serial = "0123456789ABCDEF")

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s $Serial root | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Serial shell "mkdir -p /dev/net; [ -e /dev/net/tun ] || mknod /dev/net/tun c 10 200; chmod 666 /dev/net/tun; ls -la /dev/net/tun"
& $adb -s $Serial shell "appops set app.mt5mon ACTIVATE_VPN allow; appops get app.mt5mon ACTIVATE_VPN"
& $adb -s $Serial shell "am force-stop app.mt5mon; am start -n app.mt5mon/.MainActivity" | Out-Null
Write-Output "done. アプリを起動した。設定で「つながっている」になれば OK"
