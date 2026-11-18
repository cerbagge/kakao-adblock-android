"""APK 읽기 전용 분석: dex/에셋/네이티브 라이브러리에서 도메인과 광고 SDK 패키지를 추출한다."""
import re
import sys
import zipfile
from collections import Counter, defaultdict

DOMAIN = re.compile(rb"(?<![A-Za-z0-9.\-])((?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+(?:com|net|kr|io|co\.kr|org|ai|me|app))(?![A-Za-z0-9\-])")
PKG = re.compile(rb"L((?:com|net|io|kr|org)/[a-z0-9_]+(?:/[a-z0-9_]+){1,2})/")
AD_HINT = re.compile(r"(^|[./_\-])(ad|ads|adfit|adx|adm|adn|adsvc|track|tracker|tiara|analytics|bizboard|moment|pixel|beacon|doubleclick|admob|adservice|banner|sdkvc|kakaoad|ad-?)", re.I)


def main(apk_path):
    domains = Counter()
    where = defaultdict(set)
    pkgs = Counter()
    with zipfile.ZipFile(apk_path) as z:
        for info in z.infolist():
            n = info.filename
            if not (n.endswith(".dex") or n.startswith("assets/") or n.startswith("res/raw") or n.endswith(".so") or n.endswith(".json")):
                continue
            if info.file_size > 200 * 1024 * 1024:
                continue
            data = z.read(n)
            for m in DOMAIN.finditer(data):
                d = m.group(1).decode()
                domains[d] += 1
                where[d].add(n.split("/")[0] if "/" in n else n)
            if n.endswith(".dex"):
                for m in PKG.finditer(data):
                    pkgs[m.group(1).decode().replace("/", ".")] += 1

    print("=== 광고/추적 관련으로 보이는 도메인 ===")
    for d, c in sorted(domains.items()):
        if AD_HINT.search(d):
            print(f"{c:5}  {d}   [{', '.join(sorted(where[d]))}]")

    print("\n=== 광고 관련으로 보이는 패키지 (클래스 참조 수) ===")
    for p, c in sorted(pkgs.items(), key=lambda x: -x[1]):
        if re.search(r"(adfit|\.ad(s|x|n|m)?(\.|$)|admob|advert|bizboard|moment|kakaoad|tiara|gms\.ads|applovin|unity3d\.ads|facebook\.ads|mobon|cauly|criteo|pangle|bytedance)", p):
            print(f"{c:6}  {p}")

    print("\n=== 전체 카카오/다음 계열 도메인 ===")
    for d, c in sorted(domains.items()):
        if re.search(r"(kakao|daum|kakaocdn|kakaocorp|melon)", d):
            print(f"{c:5}  {d}")


if __name__ == "__main__":
    main(sys.argv[1])
