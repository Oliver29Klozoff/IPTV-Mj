package com.iptvapp.util

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A search's results arrive after the keyboard's Search key: the first one still gets the remote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecyclerFocusTest {

    private class Rows(var count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            TextView(parent.context).apply { isFocusable = true; layoutParams = ViewGroup.LayoutParams(-1, 100) }
        ) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) { (holder.itemView as TextView).text = "Row $position" }
        override fun getItemCount() = count
    }

    @Test
    fun firstRowTakesFocusOnceTheJustArrivedResultsAreLaidOut() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val box = EditText(activity)
        val adapter = Rows(0)
        val rv = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); this.adapter = adapter }
        activity.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, -1, -2)
            addView(rv, -1, 1000)
        })
        shadowOf(Looper.getMainLooper()).idle()
        box.requestFocus()
        // Results arrive into the empty list and the focus is asked for before any layout.
        adapter.count = 20
        adapter.notifyDataSetChanged()
        rv.focusFirstItemWhenLaidOut()
        assertTrue(box.hasFocus())
        shadowOf(Looper.getMainLooper()).idle()
        val first = rv.findViewHolderForAdapterPosition(0)!!.itemView
        assertTrue("first result focused", first.hasFocus())
        assertTrue(rv.findViewHolderForAdapterPosition(0)!!.itemView.visibility == View.VISIBLE)
    }
}
