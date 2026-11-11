"""Prepare one local API-30 AVD; never starts a VM, ADB or a Windows installer."""
import pathlib

root = pathlib.Path(__file__).resolve().parents[2]
image = root / '.cache/android/system-image/x86_64'
if not (image / 'system.img').is_file():
    raise SystemExit('Prepare emulator/system image first with prepare-tests.py --emulator --google-cdn')
avds = root / '.cache/android/avd'
directory = avds / 'yilan-api30.avd'
directory.mkdir(parents=True, exist_ok=True)
config = {
    'AvdId': 'yilan-api30', 'avd.ini.encoding': 'UTF-8', 'abi.type': 'x86_64',
    'hw.cpu.arch': 'x86_64', 'hw.cpu.ncore': '2', 'hw.ramSize': '2048',
    'hw.lcd.width': '720', 'hw.lcd.height': '1280', 'hw.lcd.density': '320',
    'hw.keyboard': 'yes', 'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'swiftshader',
    'disk.dataPartition.size': '2G', 'image.sysdir.1': str(image) + '/',
    'tag.id': 'default', 'tag.display': 'Default', 'fastboot.forceColdBoot': 'yes',
    'showDeviceFrame': 'no', 'PlayStore.enabled': 'false',
}
(directory / 'config.ini').write_text('\n'.join(k + '=' + v for k, v in config.items()) + '\n', encoding='utf-8')
(avds / 'yilan-api30.ini').write_text('avd.ini.encoding=UTF-8\npath=' + str(directory) + '\ntarget=android-30\n', encoding='utf-8')
print('AVD prepared in .cache/android/avd/yilan-api30.avd')
