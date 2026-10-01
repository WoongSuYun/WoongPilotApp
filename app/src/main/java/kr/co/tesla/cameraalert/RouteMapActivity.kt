package kr.co.tesla.cameraalert

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import kr.co.tesla.cameraalert.route.RouteLedger

/** Native Kakao map: Korean map labels and an overlay made from the exact recorded GPS points. */
class RouteMapActivity : AppCompatActivity() {
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val routeIds = intent.getLongArrayExtra(EXTRA_STARTED_ATS)?.toList()
            ?: listOf(intent.getLongExtra(EXTRA_STARTED_AT, -1L))
        val routes = RouteLedger.records(this).filter { it.startedAt in routeIds }
        if (routes.isEmpty()) { finish(); return }
        title = "주행 경로 지도"
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        val points = routes.flatMap { route -> route.points.map { LatLng.from(it.latitude, it.longitude) } }
        mapView = MapView(this)
        setContentView(mapView)
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() = Unit
            override fun onMapError(error: Exception) = Unit
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                val routeColors = listOf(
                    Color.rgb(0, 168, 107),  // green
                    Color.rgb(42, 130, 228), // blue
                    Color.rgb(242, 142, 43), // orange
                    Color.rgb(156, 91, 204), // purple
                    Color.rgb(224, 82, 99)   // red
                )
                routes.forEachIndexed { index, route ->
                    val routePoints = route.points.map { LatLng.from(it.latitude, it.longitude) }
                    if (routePoints.size >= 2) {
                        val styles = RouteLineStylesSet.from(
                            RouteLineStyles.from(RouteLineStyle.from(14f, routeColors[index % routeColors.size], 3f, Color.WHITE))
                        )
                        val segment = RouteLineSegment.from(routePoints).setStyles(styles.getStyles(0))
                        kakaoMap.routeLineManager?.layer?.addRouteLine(
                            RouteLineOptions.from(segment).setStylesSet(styles)
                        )
                    }
                }
                kakaoMap.moveCamera(CameraUpdateFactory.fitMapPoints(points.toTypedArray(), 72))
            }
        })
    }

    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.resume() }
    override fun onPause() { if (::mapView.isInitialized) mapView.pause(); super.onPause() }
    override fun onDestroy() { if (::mapView.isInitialized) mapView.finish(); super.onDestroy() }
    companion object {
        const val EXTRA_STARTED_AT = "started_at"
        const val EXTRA_STARTED_ATS = "started_ats"
    }
}
