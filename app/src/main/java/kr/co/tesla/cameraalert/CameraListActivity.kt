package kr.co.tesla.cameraalert

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kr.co.tesla.cameraalert.data.CameraRepository
import kr.co.tesla.cameraalert.model.SpeedCamera
import java.util.Locale

/** Searchable view of the locally stored public speed-camera fallback database. */
class CameraListActivity : AppCompatActivity() {
    private lateinit var all: List<SpeedCamera>
    private lateinit var visible: MutableList<SpeedCamera>
    private lateinit var count: TextView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "공공데이터 카메라 목록"
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val search = EditText(this).apply {
            hint = "도로명, 제한속도, 위도·경도 검색"
            setSingleLine(true)
        }
        count = TextView(this).apply { setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt()) }
        val list = ListView(this)
        root.addView(search)
        root.addView(count)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        all = CameraRepository(this).load().sortedWith(compareBy({ it.roadName }, { it.limitKph }))
        visible = all.toMutableList()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels(visible))
        list.adapter = adapter
        updateCount()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })
        list.setOnItemClickListener { _, _, position, _ -> showDetail(visible[position]) }
    }

    private fun filter(query: String) {
        val needle = query.trim().lowercase(Locale.KOREA)
        visible = if (needle.isBlank()) all.toMutableList() else all.filter { camera ->
            "${camera.roadName} ${camera.limitKph} ${camera.latitude} ${camera.longitude}".lowercase(Locale.KOREA).contains(needle)
        }.toMutableList()
        adapter.clear()
        adapter.addAll(labels(visible))
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun labels(cameras: List<SpeedCamera>) = cameras.map {
        "${it.roadName}  ·  제한 ${it.limitKph}km/h"
    }

    private fun updateCount() {
        count.text = if (all.isEmpty()) "저장된 공공데이터 카메라가 없습니다. 먼저 업데이트해 주세요."
            else String.format(Locale.KOREA, "%,d / %,d건", visible.size, all.size)
    }

    private fun showDetail(camera: SpeedCamera) {
        AlertDialog.Builder(this)
            .setTitle("제한 ${camera.limitKph}km/h · ${camera.roadName}")
            .setMessage("위도 ${camera.latitude}\n경도 ${camera.longitude}")
            .setPositiveButton("확인", null)
            .show()
    }
}
