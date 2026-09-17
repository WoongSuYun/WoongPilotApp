# WoongPilot APK 빌드 및 업데이트 안내

## 현재 상태

- 앱 버전: `0.5.10`
- versionCode: `1001`
- APK 파일: `app/build/outputs/apk/debug/WoongPilot.apk`
- 업데이트용 서명 키: `signing/woongpilot-debug.keystore`

## PC를 바꿔가며 빌드할 때 주의할 점

PC마다 Android 기본 debug 키가 다를 수 있습니다. 따라서 회사 PC와 개인 PC에서 서로 다른 기본 debug 키로 빌드하면, 같은 앱이어도 기존 앱 위에 업데이트할 수 없습니다.

이 프로젝트는 모든 PC에서 동일한 키를 사용하도록 `signing/woongpilot-debug.keystore`를 지정해 두었습니다. `app/build.gradle.kts`의 서명 설정을 기본 debug 키로 바꾸지 않습니다.

## versionCode 규칙

Android 업데이트는 화면에 보이는 버전명보다 `versionCode`를 비교합니다.

- 다음 빌드의 versionCode는 반드시 현재 값보다 커야 합니다.
- 현재 값이 `1001`이므로 다음 업데이트는 `1002` 이상이어야 합니다.
- versionName도 함께 올리는 것을 권장합니다.

예시:

```kotlin
versionCode = 1002
versionName = "0.5.11"
```

## 빌드 명령

Windows PowerShell에서 프로젝트 루트에서 실행합니다.

```powershell
$env:GRADLE_USER_HOME = 'D:\Git\WoongPilotApp\.gradle-user'
.\gradlew.bat assembleDebug
```

APK는 다음 위치에 생성됩니다.

```text
app/build/outputs/apk/debug/WoongPilot.apk
```

## 설치 오류가 발생할 때

`앱이 설치되지 않음`이 표시되면 다음을 확인합니다.

1. 빌드한 APK의 서명이 `signing/woongpilot-debug.keystore`와 같은지 확인합니다.
2. APK의 versionCode가 휴대폰에 설치된 앱보다 높은지 확인합니다.
3. 기존 앱을 삭제하기 전에는 앱 데이터가 필요한지 먼저 확인합니다. 삭제 후 설치하면 앱 데이터가 사라질 수 있습니다.

PC를 바꾼 것 자체는 문제가 아닙니다. 모든 PC가 같은 서명 키를 사용하고 versionCode를 계속 증가시키면 기존 앱을 유지하면서 업데이트할 수 있습니다.

## 다른 PC에서 AI에게 빌드 맡기기

다른 PC에서 저장소를 clone한 뒤 AI에게 다음처럼 요청합니다.

```text
APK_BUILD_GUIDE.md를 먼저 읽어줘.
현재 versionCode를 확인하고 다음 번호로 올린 뒤 APK를 빌드해줘.
반드시 signing/woongpilot-debug.keystore를 사용하고 기본 Android debug 키는 사용하지 마.
빌드가 끝나면 APK 파일 위치, versionName, versionCode, 서명 인증서 지문을 확인해줘.
```

AI가 빌드하기 전에 다음 항목도 확인해야 합니다.

- JDK와 Android SDK가 설치되어 있어야 합니다.
- `local.properties`가 필요하며, 새 PC의 Android SDK 경로에 맞게 `sdk.dir`을 설정해야 합니다.
- `local.properties`는 Git에 올라가지 않으므로 카카오 앱 키와 공공데이터 API 키를 새 PC에 별도로 설정해야 합니다.
- `signing/woongpilot-debug.keystore` 파일이 clone한 저장소에 있는지 확인해야 합니다.
- 현재 versionCode보다 반드시 큰 번호를 사용해야 합니다. 현재 기준 다음 번호는 `1002`입니다.
- `app/build.gradle.kts`의 서명 설정을 기본 debug 키로 바꾸면 안 됩니다.
