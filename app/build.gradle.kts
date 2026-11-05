import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.onsupporter.ptt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onsupporter.ptt"
        // Opus 인코더(c2.android.opus.encoder)가 Android 10 부터다.
        // 그 아래를 받으려면 Opus 를 NDK 로 직접 넣어야 하는데, Staff
        // 폰이 그렇게까지 낡은 자리가 아니다.
        minSdk = 29
        targetSdk = 36
        versionCode = 40
        versionName = "1.6.10"

        /**
         * **폰에 들어가는 CPU 만 담는다.**
         *
         * WebRTC 는 네 가지 CPU 몫을 다 들고 온다(arm 둘, x86 둘) -
         * 그대로 두면 APK 가 15MB 에서 59MB 가 된다. x86 은 에뮬레이터
         * 몫이라 현장 폰에는 쓸 일이 없고, 팔걸이에 꽂아 쓰는 폰은
         * 전부 arm 이다.
         */
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    /**
     * **같은 알맹이, 두 몸.**
     *
     * 무전을 나르는 쪽(Link·Rtc·Voice·Duck·PttService)은 폰이든 차든
     * 같은 물건이다. 다른 것은 화면뿐이다 - 폰은 세로로 손에 쥐고,
     * 차는 2560x1440 가로 화면에 붙박여 있다.
     *
     * 그래서 갈래만 둘로 나누고 알맹이는 `src/main` 에 그대로 둔다.
     * 따로 떼어 내면 Link.kt 가 두 벌이 되고, 고칠 때마다 양쪽을
     * 건드려야 한다 - 한쪽만 고치는 날이 반드시 온다.
     *
     * 화면은 `src/phone` 과 `src/car` 가 각자 MainActivity 를 하나씩
     * 들고 있다. 이름이 같아서 PttService 의 알림 누르는 자리는
     * 어느 갈래로 구워도 제 화면을 연다.
     */
    flavorDimensions += "device"
    productFlavors {
        create("phone") { dimension = "device" }
        create("car") {
            dimension = "device"
            // 한 기계에 둘 다 깔릴 수 있게. 앱 마켓에도 따로 올라간다.
            applicationIdSuffix = ".car"
            versionNameSuffix = "-car"
        }
    }

    /**
     * 출시 서명.
     *
     * **열쇠는 이 파일에 안 적는다.** 여기 적으면 소스와 함께 어디로든
     * 딸려 간다 - 앱 서명 열쇠는 한 번 새면 되돌릴 길이 없다(같은 이름으로
     * 다른 사람이 올릴 수 있게 된다).
     *
     * `android/keystore.properties` 에 적어 두고, 그 파일은 git 이 안 본다.
     * 없으면 서명 없이 만든다 - 개발 중에는 그게 맞다.
     *
     *   storeFile=/Users/…/onsupporter.jks
     *   storePassword=…
     *   keyAlias=onsupporter
     *   keyPassword=…
     *
     * 열쇠 만들기:
     *   keytool -genkeypair -v -keystore onsupporter.jks -alias onsupporter \
     *     -keyalg RSA -keysize 2048 -validity 10000
     */
    val keyFile = rootProject.file("keystore.properties")
    val keys = Properties().apply {
        if (keyFile.exists()) keyFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keys.isNotEmpty()) {
            create("release") {
                storeFile = file(keys.getProperty("storeFile"))
                storePassword = keys.getProperty("storePassword")
                keyAlias = keys.getProperty("keyAlias")
                keyPassword = keys.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            /**
             * **열쇠가 없으면 디버그 열쇠로 서명한다.**
             *
             * 안 그러면 서명 없는 APK 가 나오고, 그것은 폰에 깔리지
             * 않는다 - 구워 놓고 못 쓰는 물건이 된다.
             *
             * 1.3.0 까지는 디버그 열쇠로 서명해 왔다. 그 열쇠는
             * **안드로이드 개발 기계마다 똑같은 열쇠**라, 남이 같은
             * 서명의 앱을 만들어 업데이트인 척 밀어 넣을 수 있다 -
             * 행사에서 무전기를 대신할 물건에 둘 자리가 아니다.
             * 2026-10-22 에 제 열쇠로 바꿨다.
             *
             * **열쇠와 비밀번호를 잃으면 이 앱은 영영 업데이트를 못 한다.**
             * 새 이름으로 다시 내는 수밖에 없다 - 따로 간수해 둔다.
             */
            signingConfig = if (keys.isNotEmpty()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 설정 → 공유의 접속 QR. 만드는 쪽(encoder)만 쓴다 - 읽기는 받는 분 카메라가 한다.
    implementation("com.google.zxing:core:3.5.3")
    /**
     * **UDP 로 소리를 나르는 길.**
     *
     * 웹소켓(TCP)은 한 조각이 늦으면 뒤엣것을 전부 세워 둔다 - 망이
     * 출렁이는 자리에서 그만큼 통째로 밀린다. WebRTC 는 늦은 것을
     * 버리고 가고, 잃은 조각은 Opus 가 복원하거나 메운다.
     *
     * 구글의 org.webrtc:google-webrtc 는 2019 에 멎었다. 지금 돌보는
     * 쪽을 쓴다 - 같은 네임스페이스(org.webrtc)라 코드는 그대로다.
     */
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    /**
     * **플레오스 SDK - 차 갈래에만.** 기어(Vehicle)로 달리는 중인지 알고, 내비(NaviHelper)에서
     * 자리를 받고 길 안내를 건다. 쓰는 자리는 car/…/Pleos.kt 하나다 - 폰 앱에는 안 들어간다.
     */
    "carImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "carImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    // 위치 - 차가 여러 감지기를 섞어 낸 자리(FusedLocation). 내비 자리는 예비로 둔다.
    "carImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    // 음성 - 읽어 주기(TTS)와 받아쓰기(STT). 둘 다 차 안에서(ON_DEVICE) 돈다.
    "carImplementation"("ai.pleos.playground:TextToSpeech:3.3.1")
    "carImplementation"("ai.pleos.playground:SpeechToText:3.3.1")
}
