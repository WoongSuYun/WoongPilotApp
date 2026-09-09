package kr.co.tesla.cameraalert.kakao

import android.app.Application
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNLanguageType
import kr.co.tesla.cameraalert.BuildConfig
import kotlinx.coroutines.*
import java.util.UUID

/** Initialization is process-wide; cancelling a screen never starts a second SDK initialization. */
object KakaoRuntime {
    private var installed = false
    private var ready = false
    private var pending: CompletableDeferred<String?>? = null
    suspend fun prepare(application: Application) = withContext(Dispatchers.Main.immediate) {
        if (ready) return@withContext
        require(BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) { "카카오 앱 키가 설정되지 않았습니다" }
        val result = pending ?: CompletableDeferred<String?>().also { request ->
            pending = request
            try {
                if (!installed) {
                    KNSDK.install(application, "${application.filesDir}/kakao")
                    installed = true
                }
                val prefs = application.getSharedPreferences("kakao_settings", 0)
                val userId = prefs.getString("user_id", null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString("user_id", it).apply()
                }
                KNSDK.initializeWithAppKey(BuildConfig.KAKAO_NATIVE_APP_KEY, BuildConfig.VERSION_NAME, userId,
                    aLangType = KNLanguageType.KNLanguageType_KOREAN, aCompletion = { error ->
                        ready = error == null
                        request.complete(error?.let { "카카오 인증 오류 ${it.code} · 앱 키와 키 해시 등록을 확인하세요" })
                        pending = null
                    })
            } catch (e: Exception) {
                pending = null; request.complete("카카오 초기화 실패 (${e.javaClass.simpleName})")
            } catch (e: LinkageError) {
                pending = null; request.complete("이 기기에서 카카오 SDK를 불러올 수 없습니다")
            }
        }
        val failure = withTimeoutOrNull(30_000) { result.await() ?: "" }
            ?: error("카카오 연결 시간 초과 · 네트워크를 확인하세요")
        check(failure.isEmpty()) { failure }
    }
}
