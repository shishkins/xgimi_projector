package os.z7.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** «Библиотека» — все приложения сеткой (последняя плитка ряда на главном экране). */
class LibraryActivity : Activity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apps = AppCatalog.load(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(44), dp(64), dp(64))
            clipChildren = false
            clipToPadding = false
        }
        column.addView(Ui.light(Ui.text(this, getString(R.string.library), 40f)))
        column.addView(Ui.text(this, resources.getQuantityString(R.plurals.library_count, apps.size, apps.size), 17f,
            secondary = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        val grid = GridLayout(this).apply {
            columnCount = COLUMNS
            clipChildren = false
            clipToPadding = false
        }
        for (app in apps) {
            grid.addView(Tiles.app(this, app, Tiles.Size.SMALL) { open(app) }, GridLayout.LayoutParams().apply {
                rightMargin = dp(24)
                bottomMargin = dp(20)
            })
        }
        column.addView(grid, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(32) })
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(column)
        })
        grid.getChildAt(0)?.requestFocus()
    }

    private fun open(app: AppEntry) {
        AppCatalog.noteLaunch(this, app.packageName)
        try {
            startActivity(Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COLUMNS = 6
    }
}
