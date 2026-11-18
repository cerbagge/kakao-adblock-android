# kakao-adblock-android

카카오톡 Android 앱의 광고 서버 DNS 요청만 막는 작은 앱입니다(APK 약 16KB).

- 카카오톡 앱은 **수정하지 않습니다.** 루팅도 필요 없습니다.
- Android의 VPN 기능을 **카카오톡 앱에만** 적용합니다. 다른 앱의 통신은 전혀 거치지 않습니다.
- VPN으로 받는 것은 **가상 DNS 주소로 가는 DNS 요청뿐**입니다. 채팅, 사진, 통화 같은 일반 데이터는 VPN을 지나가지 않습니다.
- 광고 도메인이면 "존재하지 않는 도메인(NXDOMAIN)"으로 응답하고, 나머지는 1.1.1.1(실패 시 8.8.8.8)로 그대로 전달합니다.
- 패킷이 올 때만 동작하므로 대기 중에는 CPU를 쓰지 않습니다.

## 설치와 사용

1. [Releases](../../releases)에서 최신 `kakao-adblock-*.apk`를 휴대폰으로 내려받아 설치합니다. "출처를 알 수 없는 앱" 설치 허용이 필요합니다.
2. 앱을 열고 **시작**을 누른 뒤, VPN 연결 요청을 허용합니다.
3. **카카오톡을 완전히 종료했다가 다시 실행합니다.** 이미 연결되어 있던 광고 서버 연결을 끊기 위해서입니다.
4. 부팅 후 자동으로 켜지게 하려면 `설정 → 연결 → 기타 연결 설정 → VPN → 이 앱의 톱니바퀴 → 상시 접속 VPN`을 켭니다(삼성 기준, 기종마다 메뉴 이름이 다를 수 있습니다).

### 주의
- Android는 VPN을 하나만 쓸 수 있습니다. 다른 VPN 앱을 켜면 이 앱은 자동으로 꺼집니다.
- `설정 → 개인 DNS`를 특정 서버로 **지정**해 두었다면 꺼 주십시오. 지정 모드에서는 DNS가 암호화 연결로 직접 나가서 이 앱이 볼 수 없습니다. "자동" 모드는 괜찮습니다.
- 광고를 막으면 광고 자리가 빈 칸으로 남을 수 있습니다.

## 기본 차단 목록과 근거

카카오톡 26.9.2 APK를 읽기 전용으로 분석해서 찾은 운영 서버입니다. 목록의 도메인과 그 하위 도메인이 모두 차단됩니다.

| 차단 도메인 | 포함되는 하위 도메인 | APK 안의 근거 |
|---|---|---|
| `ad.daum.net` | `display.`, `st.display.`, `analytics.`, `bc.`, `wat.`, `info.` | 광고 SDK `com.kakao.adfit`의 `https://display.ad.daum.net/` 등 |
| `survey-ad.kakao.com` | | 광고 설문 |
| `tiara.kakao.com` | | `DaumKakaoAdID` 광고 ID 쿠키 |
| `tiara.daum.net` | `stat.tiara.daum.net` | `https://stat.tiara.daum.net/track` |

앱 화면에서 목록을 직접 고칠 수 있고, **기본값** 버튼으로 되돌릴 수 있습니다.

## 카카오톡 업데이트 때 할 일

카카오톡이 업데이트되어 광고가 다시 보이면 두 가지 방법으로 새 광고 도메인을 찾을 수 있습니다.

**방법 1. 앱의 DNS 기록 확인 (휴대폰만 있으면 됨)**
앱 아래쪽의 "카카오톡 DNS 기록"에 카카오톡이 실제로 요청한 도메인이 `허용`/`차단`으로 표시됩니다. 광고가 뜬 직후의 기록에서 광고 서버로 보이는 `허용` 도메인을 찾아 차단 목록에 추가하고 **목록 저장**을 누르면 됩니다. 앱을 다시 설치할 필요는 없습니다.

**방법 2. 새 APK 분석 (PC)**
새 버전의 xapk/apk를 받아 압축을 풀고, 본체 APK(`com.kakao.talk.apk`)를 분석합니다.

```
python -I tools\scan_apk.py <압축 푼 폴더>\com.kakao.talk.apk
```

광고·추적 관련 도메인 목록과 광고 SDK 패키지가 출력됩니다. 특정 도메인이 어떤 URL로 쓰이는지 보려면 다음 명령을 사용합니다.

```
python -I tools\ctx.py <압축 푼 폴더>\com.kakao.talk.apk display.ad.daum.net
```

새로 찾은 도메인은 앱 화면에서 추가하면 됩니다. 기본 목록 자체를 바꾸려면 `src\com\cerbagge\kakaoadblock\Dns.java`의 `DEFAULT_BLOCKLIST`를 고치고 다시 빌드합니다.

## 빌드

필요한 도구: JDK, Android SDK(`platforms;android-35`, `build-tools;35.0.0`). Gradle은 필요 없습니다.

```
powershell -ExecutionPolicy Bypass -File build.ps1
```

- 결과물은 `build\kakao-adblock.apk`입니다.
- 처음 빌드할 때 서명 키(`keystore\release.jks`)와 비밀번호 파일이 만들어집니다. **이 폴더를 잃어버리면 같은 앱으로 업데이트 설치를 할 수 없으므로** 따로 백업해 두십시오.

DNS 처리 로직 단위 테스트(PC의 JDK로 실행):

```
javac -encoding UTF-8 -d %TEMP%\kab-test src\com\cerbagge\kakaoadblock\Dns.java test\DnsTest.java
java -cp %TEMP%\kab-test com.cerbagge.kakaoadblock.DnsTest
```

### 자동 빌드 (GitHub Actions)

`main`에 푸시하거나 PR을 올리면 단위 테스트와 APK 빌드가 실행되고, `v*` 태그를 푸시하면 릴리스가 자동으로 만들어집니다. 릴리스 APK는 저장소 Secrets의 `KEYSTORE_BASE64`(서명 키를 Base64로 인코딩한 값)와 `KEYSTORE_PASSWORD`로 서명합니다. Secrets가 없으면 임시 키로 서명되므로, 그 APK로는 기존 설치본을 업데이트할 수 없습니다.

## 주의

- 이 프로그램은 카카오와 관련이 없는 비공식 프로그램입니다.
- 카카오톡이 업데이트되어 광고 도메인이 바뀌면 차단되지 않을 수 있습니다.

## 라이선스

[MIT](LICENSE)
