"""지정한 도메인이 dex 안에서 어떤 URL 문자열로 쓰이는지 주변 문자열을 출력한다."""
import re
import sys
import zipfile

apk, *needles = sys.argv[1:]
URL = re.compile(rb"[\x20-\x7e]{0,80}")
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if not n.endswith(".dex"):
            continue
        data = z.read(n)
        for nd in needles:
            for m in re.finditer(re.escape(nd.encode()), data):
                s = data.rfind(b"\x00", 0, m.start()) + 1
                e = data.find(b"\x00", m.end())
                print(f"{n}: {data[s:e][-160:].decode('latin1')}")
