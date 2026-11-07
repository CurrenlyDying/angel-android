#!/usr/bin/env python3
"""Install only Angel branding resources and two existing brand-icon references.

Preserve a pre-install copy of each changed file alongside a SHA-256 manifest.
No APK installation, app-data changes, signing or publishing is performed.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]
PROJECT=next((parent for parent in ROOT.parents if (parent/'app/src/main/AndroidManifest.xml').is_file()),Path('/home/puto/AndroidStudioProjects/MyApplication'))
BACKUP=ROOT/'references/app-before-branding'


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    global PROJECT
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',type=Path,default=PROJECT)
    PROJECT=parser.parse_args().project.resolve()
    records=[]
    manifest_path=ROOT/'integration-manifest.json'
    prior={r['file']:r for r in json.loads(manifest_path.read_text())} if manifest_path.exists() else {}
    def write(relative,data):
        target=PROJECT/relative
        previous=target.read_bytes() if target.exists() else None
        saved=BACKUP/relative
        record=prior.get(str(relative))
        originally_new=record is not None and record['before_sha256'] is None
        if previous is not None and not saved.exists() and not originally_new:
            saved.parent.mkdir(parents=True,exist_ok=True);saved.write_bytes(previous)
        if previous!=data:
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        before=record['before_sha256'] if record is not None else (sha(previous) if previous is not None else None)
        records.append({'file':str(relative),'before_sha256':before,'after_sha256':sha(data),'backup':str(saved.relative_to(ROOT)) if saved.exists() else None})
    for source in sorted((ROOT/'android/res').rglob('*')):
        if source.is_file():write(Path('app/src/main/res')/source.relative_to(ROOT/'android/res'),source.read_bytes())
    relative=Path('app/src/main/java/com/bruh/angel/ui/chat/ChatScreen.kt')
    text=(PROJECT/relative).read_text()
    count=text.count('R.drawable.ic_sparkle')
    assert count in (0,2),f'Unexpected icon references ({count}); review current UI before modifying'
    if count:
        text=text.replace('R.drawable.ic_sparkle','R.drawable.ic_angel_mark')
    write(relative,text.encode())
    write(Path('fastlane/metadata/android/en-US/images/icon.png'),(ROOT/'icons/angel-prism-512.png').read_bytes())
    write(Path('fastlane/metadata/android/en-US/images/featureGraphic.png'),(ROOT/'exports/angel-feature-graphic-1024x500.png').read_bytes())
    (ROOT/'integration-manifest.json').write_text(json.dumps(records,indent=2)+'\n')
    # Install the original, OFL-licensed fonts for editing the text masters locally.
    font_root=Path.home()/'.local/share/fonts/angel-branding'
    for source in (ROOT/'fonts').rglob('*.ttf'):
        target=font_root/source.parent.name/source.name
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    subprocess.run(['fc-cache','-f',str(font_root)],check=True)
    print('Integrated',len(records),'branding files; preserved other app changes. Backups:',BACKUP)


if __name__=='__main__':main()
