# onPTT (온서포터 무전) — Pleos Connect 앱

여러 대의 차량을 하나의 채널로 묶어, 음성 무전과 위치·목적지·도착 예정 시간·배터리 잔량을
실시간으로 나누는 차량용 그룹 무전 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작이며, 심사를 위해 앱 소스를 공개합니다.

> 크롬에서 이 페이지가 자동 번역되면 한국어 문장이 어색하게 바뀝니다. 주소창의 번역 아이콘에서 「원본 보기」 를 눌러 주십시오.

![Pleos Connect 에뮬레이터에서 실행한 onPTT](docs/car.png)

- 앱 파일: [`apk/채한석_onPTT_release.apk`](apk/채한석_onPTT_release.apk) (Pleos Connect, release)
- 웹 버전(설치 없이 참여): https://onsupporter.com/ptt
- 사용 설명서: https://onsupporter.com/ptt/manual

## 주요 기능

| 기능 | 내용 |
|---|---|
| 그룹 무전(PTT) | 누르고 말하면 같은 채널 전원에게 바로 전달. 1:1 무전도 지원. WebRTC(UDP) 전송 |
| 공개방·개인방 | 닉네임만으로 공개방에 입장. 일행은 개인방으로 부르고, 부름은 버튼이나 음성으로 수락 |
| 일행 위치 지도 | 같은 채널 참가자의 위치와 거리를 지도에 표시 |
| 목적지 공유 | 내비게이션 안내 중이면 목적지·남은 시간·남은 거리를 일행에게 공유 |
| 원터치 경로 안내 | 일행의 위치 또는 같은 목적지로 차량 내비게이션 안내 시작 |
| 배터리·주행 가능 거리 | 일행의 배터리(연료) 잔량과 주행 가능 거리 공유 |
| 사고 감지 알림 | 에어백 전개 시 같은 채널에 사고 알림과 위치 전송 |
| 음악 내림 | 무전을 받거나 말하는 동안 차량 음악을 낮췄다가 되돌림 |

## 차량 특화 요소

- **음성 명령(STT)** — 스피커 그릴의 마이크 단추 또는 핸들 키로 시작합니다.
  「1번 채널」 「앞차 길 안내」 「같은 곳」 「사고 자리」 「입장코드 일이삼 사오육」 「(이름) 길 안내」 「(이름) 같은 곳」 「들어가기 / 거절」
- **음성 안내(TTS)** — 명령 결과, 1:1 요청, 개인방 부름, 일행 도착 임박, 주행 가능 거리 부족을 말로 알립니다.
- **핸들 키** — 무전 화면이 앞에 있을 때 탐색 ∨ 는 누르는 동안 송신, ∧ 는 음성 명령.
  내비가 앞에 있거나 무전에 붙어 있지 않으면 원래대로 곡 넘기기입니다.
- **기어 연동** — 주행 중(P 단 외)에는 글자 입력을 막고, P 단 정차 시 주정차 단속 구간을 알립니다.
- **차량 위치** — 차량 융합 위치(FusedLocation)를 쓰고, 끊기면 내비게이션 위치로 대체합니다.

## Pleos SDK 사용 위치

모두 [`app/src/car/java/com/onsupporter/ptt/Pleos.kt`](app/src/car/java/com/onsupporter/ptt/Pleos.kt) 한 곳에 모았습니다.
SDK 가 없거나 권한이 없어도 무전은 그대로 동작하도록, 부를 때마다 실패를 받아 넘깁니다.

| SDK | 쓰는 것 |
|---|---|
| Vehicle | 기어(`GearSelectionListener`), 배터리 잔량·연료·주행 가능 거리, 에어백 전개(`SeatAirbagDeployedListener`) |
| NaviHelper | 현재 위치, 목적지·남은 시간·거리(`getDestinationInfo`, `getRouteStateInfo`), 경로 안내 요청(`requestRoute`) |
| FusedLocation | 차량 융합 위치 |
| SpeechToText | 음성 명령 받아쓰기(기기 내 처리) |
| TextToSpeech | 음성 안내(기기 내 처리) |

## 소스 구성

하나의 무전 엔진을 스마트폰 앱과 차량 앱이 같이 씁니다. 화면만 갈래(flavor)로 나뉩니다.

```
app/src/main   공통 - 서버 연결(Link), WebRTC(Rtc), 마이크·스피커(Voice), 음악 내림(Duck),
               무전 서비스(PttService), 음성 명령 풀이(Command), 무전기 앞판 그리기(Hw)
app/src/car    Pleos Connect 화면(MainActivity)과 Pleos SDK(Pleos)
app/src/phone  스마트폰 화면, 스마트폰 받아쓰기·읽어 주기(Ear)
```

무전 서버(음성 중계·채널·위치)는 운영 서비스(onsupporter.com)이며 이 저장소에는 넣지 않았습니다.
앱은 빌드한 그대로 운영 서버에 붙습니다.

## APK 파일 위치

- [`apk/채한석_onPTT_release.apk`](apk/채한석_onPTT_release.apk) — Pleos Connect 앱 1.6.7 (release, 패키지 `com.onsupporter.ptt.car`)
- 지문: [`apk/SHA256SUMS.txt`](apk/SHA256SUMS.txt)

## 실행 방법

1. Pleos Connect 에뮬레이터를 마이크를 켠 채로 띄웁니다.
   ```bash
   emulator -avd <Pleos Connect 에뮬레이터 이름> -allow-host-audio
   ```
2. APK 를 운전자(사용자 10)에게 설치하고 실행합니다.
   ```bash
   adb install --user 10 apk/채한석_onPTT_release.apk
   adb shell am start --user 10 -n com.onsupporter.ptt.car/com.onsupporter.ptt.MainActivity
   ```
3. 처음 실행하면 권한을 묻습니다. 모두 허용합니다(아래 「필요한 설정」).
4. **공개방**에 이름(닉네임)을 넣고 「접속」 을 누릅니다. 로그인이나 회원가입은 없습니다.
5. 무전 상대를 하나 더 세웁니다 - PC 브라우저에서 https://onsupporter.com/ptt 를 열고 다른 이름으로
   공개방에 들어가면 같은 「로비」 채널에 함께 들어옵니다.
6. 아래 송신 단추를 누르고 말하면 상대에게 들리고, 상대가 말하면 차 스피커로 나옵니다.

### 기능별로 해 보기

| 기능 | 해 보는 법 |
|---|---|
| 음성 명령 | 스피커 그릴 오른쪽 아래 마이크 단추를 누르고 「2번 채널」 「로비」 처럼 말합니다 |
| 핸들 키 | `adb shell cmd car_service inject-key -t 1500 88` (탐색 ∨ 1.5초 = 송신), `adb shell cmd car_service inject-key 87` (탐색 ∧ = 음성 명령) |
| 채널 이동 | 노브를 돌리거나 표시창의 채널 칩을 누릅니다 |
| 일행 위치·경로 안내 | 오른쪽 지도에서 일행을 누르면 내비게이션 안내를 시작합니다 |
| 개인방 | 「개인방」 을 고르고 여섯 자리 입장코드로 들어갑니다(코드는 웹에서 개인방을 만들면 나옵니다) |
| 음악 내림 | 미디어를 틀어 둔 채 무전을 받으면 음악이 낮아졌다가 돌아옵니다 |

## 테스트 환경 및 필요한 설정

**테스트한 환경**

- Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34, arm64) · macOS(Apple Silicon)
- 상대: PC 크롬 브라우저(https://onsupporter.com/ptt)
- 서버: 운영 서버 onsupporter.com (앱에 들어 있어 따로 설정할 것 없음)

**필요한 설정**

- **인터넷**: onsupporter.com(443) 에 닿아야 합니다. 음성은 WebRTC(UDP)로 나르고, UDP 가 막힌 망에서는
  웹소켓으로 자동으로 넘어갑니다.
- **권한**(처음 실행 때 묻습니다): 마이크, 위치, 알림, 그리고 Pleos 차량 권한(기어·에너지·안전·내비·위치·음성).
  Pleos 권한이 없어도 무전은 되고, 해당 차량 기능만 빠집니다.
- **마이크**: 에뮬레이터를 `-allow-host-audio` 로 띄워야 맥(PC) 마이크가 들어갑니다. 없으면 무음이 나갑니다.
  에뮬레이터는 켜질 때의 PC 소리 장치를 잡으므로, 이어폰을 바꿨다면 에뮬레이터를 다시 켭니다.
- **소리 크기**: 에뮬레이터 차 볼륨이 45칸 중 20칸으로 시작해 작게 들립니다. 볼륨 키로 올립니다.
- **에뮬레이터 사용자**: 앱은 운전자(사용자 10)에게 설치합니다(`--user 10`).

## 직접 빌드하기

필요한 것: JDK 17, Android SDK(compileSdk 36). Pleos SDK 는 로그인 없이 Pleos 메이븐 저장소에서 받습니다.

```bash
./gradlew assembleCarRelease    # Pleos Connect 앱 → app/build/outputs/apk/car/release/
./gradlew assemblePhoneRelease  # 스마트폰 앱(무전 엔진을 같이 씁니다)
```

서명 열쇠(`keystore.properties`)가 없으면 안드로이드 디버그 열쇠로 서명합니다.

## 저작권

Copyright © 2026 온서포터. 공모전 심사를 위해 공개한 소스입니다. 별도 허락 없이 재배포하거나 상업적으로 쓸 수 없습니다.
