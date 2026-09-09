# 웅파일럿 (WoongPilot)

테슬라 차량과 BLE 카드키로 연결된 동안 휴대폰 GPS를 사용해 전방 과속단속 카메라를 안내하는 Android 앱입니다. 카카오모빌리티 KNSDK의 안전 운행 정보를 우선 사용하고, Tesla Fleet API는 차량 상태를 상단 카드에 표시하는 용도로 사용합니다.

> 이 프로젝트는 운전자 보조 안내 도구입니다. 실제 도로 표지, 차량 계기판 및 법규를 우선해야 하며 카메라 데이터의 완전성이나 실시간성을 보장하지 않습니다.

## 주요 기능

| 영역 | 동작 |
| --- | --- |
| BLE 카드키 | 차량별 Android Keystore P-256 키를 만들고 Tesla BLE(VCSEC)로 `VEHICLE_MONITOR` 역할의 키 등록을 요청합니다. |
| 자동 감시 | 앱이 열린 상태에서 등록된 차량 BLE 연결과 키 확인이 성공하면 감시를 시작합니다. GPS·카카오 안전 안내·경고 음성은 이 상태에서만 동작합니다. |
| 안전 운행 안내 | 과속·신호단속 카메라, 과속방지턱, 어린이보호구역을 기본 안내합니다. 어린이/교통사고 다발 구간, 급회전, 철도건널목, 미끄럼 주의는 항목 설정에서 선택할 수 있습니다. |
| 카카오 우선 / 공공데이터 보조 | 카카오 안전 운행 피드가 최신이면 이를 사용하고, 응답이 오래되었을 때만 공공데이터포털의 저장된 카메라 목록으로 보조 탐색합니다. |
| 음성 안내 | 기본은 기기에 설치된 한국어 TTS 음성입니다. 운전 카드의 **Gemini AI 음성 설정**에서 사용자가 입력한 Gemini API 키와 AI 목소리를 선택할 수 있으며, 실패하면 기본 TTS로 자동 전환됩니다. |
| Tesla 상태 카드 | Tesla 로그인 후 배터리, 예상 주행거리, 기어, 속도, 잠금, 도어, 센트리, 온도, 소프트웨어 등을 표시합니다. 차량 깨우기와 수동 새로고침을 제공합니다. |

## 동작 구조

```text
                         +-----------------------------+
                         | Tesla Developer / Fleet API |
                         +--------------+--------------+
                                        ^ OAuth / 차량 데이터 / wake_up
                                        |
+-------------------+       HTTPS       |       +----------------------------+
| Android 웅파일럿   +-------------------+-------+ Cloudflare Worker          |
|                   |   opaque session  |       | OAuth 교환 · KV 토큰 보관   |
| TeslaAuth.kt      |                   |       | 공개키 well-known 제공      |
+--------+----------+                   |       +----------------------------+
         |
         | BLE 카드키 확인
         v
+-------------------+      휴대폰 GPS       +-------------------------------+
| Tesla 차량         +----------------------+ 카카오 KNSDK / 공공데이터 API |
+-------------------+                      +---------------+---------------+
                                                           |
                                                           v
                                             전방 카메라 판정 · 한국어 TTS 경고
```

Tesla OAuth access/refresh token은 Android 앱에 전달하지 않습니다. 앱은 Worker KV 세션을 가리키는 임의의 세션 ID만 보관합니다.

## 프로젝트 구성

| 경로 | 역할 |
| --- | --- |
| `app/src/main/java/.../monitor/CameraMonitorService.kt` | 포그라운드 서비스, Tesla BLE 연결, GPS 감시, 카메라 경고와 TTS 호출 |
| `app/src/main/java/.../kakao/` | KNSDK 초기화 및 안전 운행 카메라 정보 변환 |
| `app/src/main/java/.../TeslaAuth.kt` | Worker를 통한 Tesla OAuth 세션·차량 정보 요청 |
| `app/src/main/java/.../voice/AlertSpeaker.kt` | 시스템 한국어 TTS 설정·미리 듣기·경고 발화 |
| `app/src/main/java/.../voice/GeminiTts.kt` | 사용자 입력 Gemini 키의 Android Keystore 암호화 저장, Gemini TTS 생성·재생, 기본 TTS 전환 |
| `cloudflare/tesla-auth-worker.js` | Tesla OAuth 교환, Fleet API 프록시, Worker KV 세션 관리 |
| `app/build.gradle.kts` | Android 앱 ID, KNSDK 의존성 및 로컬 키의 `BuildConfig` 주입 |

## 요구 사항

- Android 8.0(API 26) 이상, Android SDK 35, JDK 17
- Tesla BLE 카드키 등록을 지원하는 차량
- 카카오모빌리티 KNSDK 사용 권한과 카카오 Native 앱 키
- Tesla Fleet API 애플리케이션, Cloudflare Workers 및 Workers KV
- 공공데이터 보조 기능을 쓸 경우 공공데이터포털 서비스 키

## Android 로컬 설정

루트의 `local.properties`는 Git에 포함하지 않습니다. 아래처럼 **본인의 값만** 넣습니다.

```properties
KAKAO_NATIVE_APP_KEY=카카오_네이티브_앱_키
DATA_GO_KR_SERVICE_KEY=공공데이터포털_서비스_키
```

`KAKAO_NATIVE_APP_KEY`는 Android SDK 초기화를 위해 APK의 `BuildConfig`에 포함됩니다. 따라서 서버 비밀키처럼 취급할 값은 아니지만, 카카오 콘솔에서 반드시 패키지명과 서명 키 해시를 등록해 사용 범위를 제한해야 합니다. Tesla 고객 비밀번호(client secret), Tesla 개인키, Gemini 키 등은 `local.properties`나 앱 소스에 넣지 않습니다.

## 카카오모빌리티 / 카카오디벨로퍼스 설정

이 앱은 `com.kakaomobility.knsdk:knsdk_ui`를 사용하며 `KNSDK.initializeWithAppKey()`에 **Native 앱 키**를 전달합니다.

1. 카카오디벨로퍼스에서 앱을 생성하거나 사용할 앱을 엽니다.
2. **앱 → 플랫폼 키 → Native 앱 키 → Android 앱 정보**에서 Android 플랫폼을 추가합니다.
3. 패키지명을 `kr.co.tesla.cameraalert`로 등록합니다.
4. 현재 개발/배포에 사용하는 서명 인증서의 키 해시를 등록합니다. 개발자마다 디버그 키 해시가 다를 수 있으므로 필요한 모든 개발용 해시를 추가합니다.
5. Native 앱 키를 `local.properties`의 `KAKAO_NATIVE_APP_KEY`에 넣습니다.
6. 앱의 **카카오 연결 확인**에서 초기화가 성공하는지 확인합니다.

릴리즈 서명 또는 Google Play App Signing을 사용하면 릴리즈용 키 해시도 별도로 등록해야 합니다. `invalid android_key_hash` 또는 `C103` 오류가 나오면 패키지명, 실제 설치 APK의 서명 키 해시, Native 앱 키를 먼저 확인합니다.

카카오 플랫폼 정보와 키 해시 관리 위치는 카카오의 [앱 설정 문서](https://developers.kakao.com/docs/ko/app-setting/app) 및 [Android 시작하기](https://developers.kakao.com/docs/ko/android/getting-started)를 기준으로 합니다. KNSDK 상품 사용 권한·약관은 해당 카카오모빌리티 개발자 계약 상태도 확인해야 합니다.

## 공공데이터포털 보조 카메라 데이터

카카오 피드가 최신일 때는 공공데이터를 동시에 울리지 않습니다. 카카오 피드가 오래되거나 연결이 실패할 때만 앱 시작 시 내려받아 둔 목록을 사용합니다.

1. [전국무인교통단속카메라표준데이터 API](https://www.data.go.kr/data/15028200/standard.do)를 활용 신청하고 서비스 키를 발급받습니다.
2. `DATA_GO_KR_SERVICE_KEY`를 `local.properties`에 넣습니다.
3. 앱 시작 시 갱신이 성공하면 내부 저장소의 `cameras.json`을 원자적으로 교체합니다. 갱신 실패 시 이전에 저장한 정상 데이터가 있으면 계속 사용합니다.

과속 단속으로 판별되는 행만 가져오며, 좌표 또는 제한속도 누락 행은 제외합니다. 데이터의 갱신 시차, 평행 도로, 고가도로, 구간단속 평균속도는 보장하지 않습니다.

## Tesla Fleet API + Cloudflare Worker 설정

### 1. Tesla Developer 애플리케이션 만들기

Tesla Developer에서 Fleet API 애플리케이션을 만듭니다. Worker의 `ORIGIN` 값을 예로 들면 다음 값들은 서로 정확히 일치해야 합니다.

| Tesla 콘솔 항목 | 예시 값 |
| --- | --- |
| OAuth 부여 유형 | `authorization-code`, `client-credentials` |
| 허용된 출처(Allowed origin) | `https://<worker-domain>` |
| 허용된 리디렉션 URI | `https://<worker-domain>/auth/tesla/callback` |

Tesla에서 제공한 client ID는 Worker의 `CLIENT_ID`에 설정하고, 고객 비밀번호(client secret)는 Cloudflare Secret으로만 저장합니다. redirect URI나 origin을 바꾸면 Tesla 콘솔, Worker의 `ORIGIN`, Android의 `TeslaAuth.kt` `ORIGIN`을 함께 수정해야 합니다.

### 2. P-256 공개키 만들기

Tesla Fleet API는 `prime256v1`(secp256r1) EC 공개키를 앱 도메인의 well-known 경로에 계속 제공해야 합니다.

```powershell
openssl ecparam -name prime256v1 -genkey -noout -out tesla-private-key.pem
openssl ec -in tesla-private-key.pem -pubout -out tesla-public-key.pem
```

- `tesla-private-key.pem`은 비밀입니다. 저장소, Worker 변수, 채팅에 올리지 않습니다.
- `tesla-public-key.pem`의 PEM 전체를 Cloudflare `TESLA_PUBLIC_KEY` 값으로 등록합니다.
- Worker는 `https://<worker-domain>/.well-known/appspecific/com.tesla.3p.public-key.pem`에서 이 공개키를 제공합니다.

Tesla의 [Partner Endpoints 문서](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints)는 이 공개키가 정확한 well-known 경로에 지속적으로 있어야 하고, 도메인이 Tesla의 allowed origin과 일치해야 한다고 명시합니다.

### 3. Cloudflare Worker 및 KV 구성

Cloudflare Workers에서 Worker를 만들고 `cloudflare/tesla-auth-worker.js` 내용을 배포합니다. Workers KV namespace를 하나 만든 뒤 Worker에 다음 이름으로 바인딩합니다.

| 이름 | Cloudflare 타입 | 설명 |
| --- | --- | --- |
| `TESLA_CLIENT_SECRET` | Secret | Tesla Developer에서 발급한 고객 비밀번호(client secret) |
| `TESLA_PUBLIC_KEY` | Variable 또는 Secret | 위에서 만든 공개 PEM. 공개값이지만 줄바꿈이 보존되게 입력합니다. |
| `TESLA_SESSIONS` | KV namespace binding | OAuth access/refresh token 세션과 Fleet 등록 플래그 |

Worker는 다음 역할을 수행합니다.

- `/auth/tesla/login`에서 `state` 쿠키를 만들고 Tesla OAuth 로그인으로 이동
- `/auth/tesla/callback`에서 authorization code를 Worker 내부에서 토큰으로 교환
- 64자리 임의 세션 ID만 `woongpilot://oauth/callback`으로 Android 앱에 반환
- 세션 토큰을 KV에 약 89일 TTL로 보관하고 필요 시 refresh token으로 갱신
- 최초 로그인 전에 `POST /api/1/partner_accounts`로 Fleet API partner registration 시도
- 차량 목록, `vehicle_data`, `wake_up` 요청을 Worker 경유로 전달

배포 후 아래 주소를 열어 PEM이 그대로 내려오는지 확인합니다.

```text
https://<worker-domain>/.well-known/appspecific/com.tesla.3p.public-key.pem
```

공개키 또는 Worker 도메인을 변경했다면 Tesla 콘솔의 origin/redirect URI도 바꾸고, KV의 `fleet_registered` 값을 삭제한 뒤 다시 로그인해 등록을 수행합니다.

### 4. Android 앱과 연결

`app/src/main/java/kr/co/tesla/cameraalert/TeslaAuth.kt`의 `ORIGIN`은 위 Worker URL과 같아야 합니다. 앱에서 **Tesla 계정 로그인**을 누르고 권한을 승인한 다음 차량을 고르면 됩니다.

차량 데이터는 차량이 절전·오프라인이면 Tesla API에서 408을 반환할 수 있습니다. **차량 깨우고 정보 가져오기**는 `vehicle_cmds` scope를 요청하며, 차량 상태가 온라인이 될 때까지 잠시 기다린 뒤 다시 읽습니다. 이는 읽기 전용 상태 표시와 별도의 원격 명령 권한이므로 필요 범위를 넘는 권한을 추가하지 않습니다.

## 카드키 등록과 자동 감시

1. 차량 안에서 Bluetooth와 정확한 휴대폰 위치를 켭니다.
2. VIN을 입력하고 **카드키로 앱 등록**을 누릅니다.
3. Tesla 화면의 안내에 따라 실물 카드키를 리더에 대고 등록 요청을 승인합니다.
4. 앱이 차량의 키 목록에서 이 휴대폰 공개키를 확인하면 등록이 완료됩니다.
5. 기본값으로 감시를 즉시 시작합니다. 이후에는 앱을 열면 등록 차량을 검색하고 키 확인이 성공한 경우에만 자동 감시를 시작합니다.

앱이 차량에 가까워진 것만으로 Android 앱 화면을 강제로 실행하지는 않습니다. Android의 백그라운드 실행 정책상 앱을 열었을 때 또는 이미 실행 중인 포그라운드 감시 서비스에서 연결을 유지하는 방식입니다. 감시 중에는 지속 알림이 표시되며, 앱을 뒤로 보내거나 화면을 꺼도 동작합니다. 앱 강제 종료, 재부팅, 권한 해제, 수동 중지 시에는 멈춥니다.

## 카메라 판정 및 음성

- GPS 위치 나이 5초 이하, 정확도 40m 이하, 방향·속도 보유, 속도 2m/s(약 7.2km/h) 이상일 때만 판정합니다.
- 전방 700m, 진행 방향 ±35° 안의 가장 가까운 카메라를 선택합니다.
- 동일 카메라 또는 거의 같은 장소의 중복 안내는 2분 동안 억제합니다.
- 기본 문구는 `전방 500미터, 제한속도 60킬로미터, 과속 단속 카메라입니다.` 형식입니다.
- Android 시스템 TTS가 준비되지 않은 순간에는 짧은 경고음으로 대체합니다.
- 기본 음성의 말하기 속도는 0.6x~1.4x 범위에서 0.1x 단위로 저장되며, 설정을 다시 열어도 저장한 값이 그대로 표시됩니다.

### 안전 안내 항목

운전 카드의 **안전 안내 항목 설정**에서 항목별 안내를 켜거나 끌 수 있습니다. 카카오 안전 운행의 경로상 데이터를 사용하므로 방지턱·어린이보호구역 등은 카카오 데이터가 제공되는 구간에서만 안내됩니다. 카카오 연결이 불가능한 경우에는 기존 공공데이터 기반 과속단속 카메라 보조 탐색만 동작합니다.

- 기본 켜짐: 과속·신호 단속 카메라, 과속방지턱, 어린이보호구역
- 선택 켜짐: 어린이 사고 다발 구간, 교통사고 다발 구간, 급회전 구간, 철도 건널목, 미끄럼 주의 구간

### Gemini AI 음성 설정

운전 카드의 **Gemini AI 음성 설정**에서 본인 Gemini API 키를 입력하고 원하는 목소리를 고른 뒤 저장합니다. Android Keystore의 기기 전용 AES 키로 암호화한 값만 앱 설정에 보관합니다. 개인용 앱을 위해 **저장된 Gemini 키 보기/숨기기**와 **저장된 Gemini 키 삭제**를 제공합니다. 보기 기능을 사용하면 키가 화면에 평문으로 표시되므로 스크린샷·화면 공유에 주의합니다.

- Gemini AI 음성은 인터넷 연결과 Gemini API 사용 가능 상태가 필요합니다. 호출이 실패·지연되거나 기능이 꺼져 있으면 기존 휴대폰 TTS가 경고를 이어서 안내합니다.
- 동일한 모델·목소리·안내 문구의 음성은 앱 내부 캐시에서 재생하므로 Gemini API를 다시 호출하지 않습니다. 캐시는 최대 40개 또는 12MB로 제한되며, 오래된 항목부터 자동 정리됩니다. 운전 카드의 **Gemini 음성 보관함**에서 저장된 문구 목록을 확인하고 항목별로 재생할 수 있으며, 설정에서 **Gemini 음성 캐시 비우기**로 즉시 삭제할 수 있습니다.
- API 키는 APK, `local.properties`, 소스 코드, 로그에 넣지 않습니다. 앱 안에서 사용자가 직접 입력한 키만 사용합니다.
- 모바일 앱에서 API 키를 직접 호출하는 구조는 서버 프록시보다 노출 위험이 큽니다. 개인 전용·제한된 Gemini 키를 사용하고, 실제 공개 배포용은 별도 인증 서버/프록시로 교체하는 것을 권장합니다.
- 현재 구현은 Gemini TTS `gemini-3.1-flash-tts-preview` 모델과 Google 공식 음성 이름을 사용합니다. 최신 Interactions REST API의 `steps → model_output → content(type: audio)` 응답을 읽고 PCM/WAV를 재생합니다. 모델·요금·할당량은 Gemini 콘솔에서 별도로 확인합니다.

## 빌드

JDK 17과 Android SDK 35가 필요합니다.

```powershell
./gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

디버그 APK 경로:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 검증 체크리스트

- 카카오 Native 앱 키, 패키지명, 디버그/릴리즈 키 해시가 모두 맞는지
- Worker well-known URL이 유효한 PEM을 반환하는지
- Tesla 콘솔의 origin과 redirect URI가 Worker 설정과 일치하는지
- Tesla OAuth 승인 후 차량 목록과 차량 이름이 표시되는지
- 차량이 절전일 때 wake 요청 후 데이터가 다시 읽히는지
- 카드키 승인 후 차량 키 목록에서 공개키가 확인되는지
- 차량 BLE 연결 성공 시에만 GPS 감시와 카카오 안내가 시작되는지
- 화면 꺼짐, BLE 단절/복귀, GPS·Bluetooth 권한 해제, 수동 중지에서 상태가 올바르게 바뀌는지
- 실제 도로에서 카메라 거리, 음성 안내, 중복 억제가 기대와 맞는지
- Gemini 키를 입력한 뒤 AI 음성 미리 듣기와, 인터넷 단절 시 기본 TTS 전환이 동작하는지

## 보안 규칙

- `local.properties`, 서명키(`.jks`, `.keystore`), PEM 파일, Worker secret은 커밋하지 않습니다.
- Tesla client secret, access token, refresh token, Tesla backup passcode는 로그·스크린샷·이슈·채팅에 남기지 않습니다.
- Gemini API 키도 로그·스크린샷·이슈·채팅에 남기지 않습니다. 키가 노출된 것으로 의심되면 Google AI Studio/Google Cloud에서 즉시 폐기하고 새 키로 교체합니다.
- Cloudflare KV는 토큰 저장소이므로 권한을 최소화하고 Worker 로그에 세션 본문을 출력하지 않습니다.
- APK를 공개 배포할 때는 카카오 Native 앱 키의 패키지명·키 해시 제한, Tesla OAuth redirect URI 제한, Worker rate limiting과 로그 점검을 함께 검토합니다.

## 참고 문서

- [Tesla Fleet API — Partner Endpoints](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints)
- [Tesla Fleet API — Third-party OAuth](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens)
- [Cloudflare Workers KV](https://developers.cloudflare.com/kv/)
- [카카오디벨로퍼스 — 앱 설정](https://developers.kakao.com/docs/ko/app-setting/app)
- [카카오디벨로퍼스 — Android 시작하기](https://developers.kakao.com/docs/ko/android/getting-started)
- [공공데이터포털 — 전국무인교통단속카메라표준데이터](https://www.data.go.kr/data/15028200/standard.do)
