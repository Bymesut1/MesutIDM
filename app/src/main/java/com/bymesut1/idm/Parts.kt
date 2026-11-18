package com.bymesut1.idm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** IDM'deki gibi parça durum çubuğu: gri = bekliyor, mavi = iniyor, yeşil = bitti. */
class PartsBar(ctx: Context) : View(ctx) {
    var parts: ByteArray? = null
    var allDone = false
    private val p = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (8 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(c: Canvas) {
        p.color = Color.parseColor("#DDDDDD")
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        val a = parts
        if (a == null || a.isEmpty()) return
        val cw = width.toFloat() / a.size
        for (i in a.indices) {
            val s = a[i].toInt()
            if (s == 0 && !allDone) continue
            p.color = if (s == 2 || allDone) Color.parseColor("#1B5E20") else Color.parseColor("#1976D2")
            c.drawRect(i * cw, 0f, (i + 1) * cw - 0.5f, height.toFloat(), p)
        }
    }
}
