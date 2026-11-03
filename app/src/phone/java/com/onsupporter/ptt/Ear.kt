package com.onsupporter.ptt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * **폰의 음성 명령 - 받아쓰기와 말로 답하기.** 폰에 든 받아쓰기(SpeechRecognizer)와 읽어 주기
 * (TextToSpeech)를 쓴다 - 우리가 돈을 내는 길이 아니다(운영비 원칙). 차 앱은 플레오스 것을 쓴다(Pleos).
 *
 * 받아쓰기는 메인 실에서만 부른다(안드로이드 규칙). 말이 없으면 [HEAR_MAX] 에 끊는다.
 */
object Ear {
    @Volatile var listening = false; private set
    /** 받아쓴 말. 빈 글이면 못 알아들은 것. 메인 실에서 부른다. */
    var onHeard: ((String) -> Unit)? = null
    /** 듣는 중이 바뀌었다(단추 고리). */
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsUp = false
    private const val HEAR_MAX = 8000L
    private val hearMax = Runnable { if (listening) { rec?.cancel(); end(""); } }

    private fun end(said: String) {
        main.removeCallbacks(hearMax)
        if (!listening) return
        listening = false; onChange?.invoke()
        onHeard?.invoke(said)
    }

    /** 듣기를 시작한다. 듣는 중이면 멈춘다. 폰에 받아쓰기가 없으면 false. */
    fun listen(ctx: Context): Boolean {
        if (listening) { rec?.cancel(); main.removeCallbacks(hearMax); listening = false; onChange?.invoke(); return true }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return false
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext).also { rec = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                end(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim())
            }
            override fun onError(error: Int) { end("") }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        return try {
            r.startListening(i)
            listening = true; onChange?.invoke()
            main.postDelayed(hearMax, HEAR_MAX)
            true
        } catch (t: Throwable) { false }
    }

    /** 한 일을 말로 돌려준다 - 화면을 안 보고 했으니 귀로 확인한다. */
    fun say(ctx: Context, text: String) {
        val t = tts
        if (t != null && ttsUp) { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ptt-cmd"); return }
        if (t != null) return   // 아직 켜지는 중 - 이번 말은 넘긴다
        tts = TextToSpeech(ctx.applicationContext) { st ->
            ttsUp = st == TextToSpeech.SUCCESS
            if (ttsUp) {
                tts?.language = Locale.KOREAN
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ptt-cmd")
            }
        }
    }
}
